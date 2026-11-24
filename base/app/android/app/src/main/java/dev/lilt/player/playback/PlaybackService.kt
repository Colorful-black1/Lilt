package dev.lilt.player.playback

import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.lilt.player.MainActivity
import dev.lilt.player.data.LiltDatabase
import dev.lilt.player.data.OnlineLyricsEngine
import dev.lilt.player.library.EQ_BANDS
import dev.lilt.player.library.EQ_FREQUENCIES
import dev.lilt.player.library.EqProfile
import dev.lilt.player.library.LrcDocument
import dev.lilt.player.library.PreampMode
import dev.lilt.player.library.abLoopReady
import dev.lilt.player.library.abLoopShouldJump
import dev.lilt.player.library.accentArgb
import dev.lilt.player.library.balanceOffsetsDb
import dev.lilt.player.library.bassBoostDb
import dev.lilt.player.library.clampFloatingFontSize
import dev.lilt.player.library.parseLrc
import dev.lilt.player.library.stopAfterShouldStop
import dev.lilt.player.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * One foreground media session owns playback, the notification and the
 * lock-screen controls. The activity only talks to it through a
 * [androidx.media3.session.MediaController].
 *
 * Sleep timer state lives here, not in the activity, so the timer keeps
 * counting with the screen off and while the app is not in the foreground.
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var playback: FadePlayer? = null
    private var session: MediaSession? = null
    private var dynamics: DynamicsProcessing? = null
    private var legacyEq: Equalizer? = null
    private var virtualizer: Virtualizer? = null
    private val prefs by lazy { getSharedPreferences("lilt_effects", MODE_PRIVATE) }
    private val settingsPrefs by lazy { getSharedPreferences("lilt_settings", MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var profile = EqProfile()
    private var perDevice = true
    private var deviceKey = "speaker"
    private var deviceName = "Phone speaker"
    private var virtualizerAvailable = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputDevice(loadProfile = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputDevice(loadProfile = true)
        }
    }
    private var fadeRunnable: Runnable? = null
    private var sleep = SleepState()
    private var abStartMs = -1L
    private var abEndMs = -1L
    private var abLastJumpAt = 0L
    private var stopAfterCurrent = false

    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: FloatingLyricsOverlay? = null
    private var overlayLyrics: LrcDocument? = null
    private var overlayLyricsMediaId = -1L
    private var overlayLyricsOffset = 0
    private var overlayLyricsJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private val overlayActions = object : FloatingLyricsActions {
        override fun togglePlay() {
            val exo = player ?: return
            if (exo.mediaItemCount == 0) {
                openApp()
            } else if (exo.isPlaying) {
                playback?.pause()
            } else {
                playback?.play()
            }
        }

        override fun previous() {
            val exo = player ?: return
            if (exo.currentPosition > 10_000L) exo.seekTo(0) else exo.seekToPreviousMediaItem()
        }

        override fun next() {
            player?.seekToNextMediaItem()
        }

        override fun close() {
            settingsPrefs.edit().putBoolean("floating_lyrics", false).apply()
            syncOverlay()
        }

        override fun lock() {
            settingsPrefs.edit().putBoolean("floating_lyrics_locked", true).apply()
            overlay?.applyState(overlayState())
        }

        override fun openApp() {
            startActivity(
                Intent(this@PlaybackService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
    }
    private val overlayTicker = object : Runnable {
        override fun run() {
            if (overlay?.showing == true) {
                overlay?.applyState(overlayState())
                mainHandler.postDelayed(this, 250)
            }
        }
    }
    private val widgetTicker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (exo.isPlaying) {
                WidgetRenderer.update(this@PlaybackService, exo)
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }
    private val abTicker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (!abLoopReady(abStartMs, abEndMs)) return
            if (exo.isPlaying && abLoopShouldJump(abStartMs, abEndMs, exo.currentPosition)) {
                val now = SystemClock.elapsedRealtime()
                if (now - abLastJumpAt > 300L) {
                    abLastJumpAt = now
                    exo.seekTo(abStartMs)
                }
            }
            mainHandler.postDelayed(this, 100)
        }
    }
    private val stopAfterTicker = object : Runnable {
        override fun run() {
            if (!stopAfterCurrent) return
            val exo = player ?: return
            if (!exo.isPlaying) return
            if (stopAfterShouldStop(true, true, exo.duration, exo.currentPosition)) {
                exo.pause()
                exo.seekTo(0L)
                setStopAfter(false)
                return
            }
            mainHandler.postDelayed(this, 100)
        }
    }
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "floating_lyrics" -> syncOverlay()
                "floating_lyrics_locked",
                "floating_font_size",
                "lyrics_opacity",
                "accent",
                "language",
                -> {
                    overlay?.applyState(overlayState())
                    WidgetRenderer.update(this, player)
                }
            }
        }
    private var settingsListenerRegistered = false

    override fun onCreate() {
        super.onCreate()
        loadEffectState()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val audioManager = getSystemService(AudioManager::class.java)
        val audioSession = audioManager.generateAudioSessionId()
        exo.setAudioSessionId(audioSession)
        player = exo
        val wrapper = FadePlayer(exo)
        playback = wrapper
        attachEqualizer(audioSession)
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler) }
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                overlay?.applyState(overlayState())
                WidgetRenderer.update(this@PlaybackService, player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                loadOverlayLyrics(mediaItem?.mediaId?.toLongOrNull() ?: -1L)
                maybeFetchOnlineLyrics(mediaItem)
                clearAbLoop()
                if (stopAfterCurrent) {
                    if (
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        exo.pause()
                        exo.seekTo(0L)
                    }
                    setStopAfter(false)
                }
                overlay?.applyState(overlayState())
                WidgetRenderer.update(this@PlaybackService, exo)
                if (sleep.mode == MODE_OFF) return
                if (sleep.pendingStop) {
                    stopForSleep()
                    return
                }
                if (
                    sleep.mode == MODE_TRACKS &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) {
                    val left = (sleep.tracksLeft - 1).coerceAtLeast(0)
                    sleep = sleep.copy(tracksLeft = left)
                    if (left <= 0) stopForSleep()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startSleepTicker()
                    startWidgetTicker()
                    if (stopAfterCurrent) startStopAfterTicker()
                    maybeFetchOnlineLyrics(exo.currentMediaItem)
                } else {
                    stopSleepTicker()
                    stopWidgetTicker()
                }
                overlay?.applyState(overlayState())
                WidgetRenderer.update(this@PlaybackService, exo)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && sleep.mode != MODE_OFF) {
                    stopForSleep()
                }
                overlay?.applyState(overlayState())
                WidgetRenderer.update(this@PlaybackService, exo)
            }
        })
        session = MediaSession.Builder(this, wrapper)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_EQ_SET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_EQ_GET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SLEEP_SET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SLEEP_GET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SLEEP_CANCEL, Bundle.EMPTY))
                        .add(SessionCommand(CMD_AB_SET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_AB_GET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_AB_CLEAR, Bundle.EMPTY))
                        .add(SessionCommand(CMD_STOP_AFTER_SET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_STOP_AFTER_GET, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .setAvailablePlayerCommands(
                            androidx.media3.common.Player.Commands.Builder().addAllCommands().build()
                        )
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_EQ_SET -> {
                            val wantedPerDevice = args.getBoolean("perDevice", perDevice)
                            if (wantedPerDevice && !perDevice) saveEffectState()
                            perDevice = wantedPerDevice
                            var incoming = profile.copy(
                                enabled = args.getBoolean("enabled", profile.enabled),
                                preampMode = PreampMode.fromId(
                                    args.getString("preampMode") ?: profile.preampMode.id
                                ),
                                headroomDb = args.getFloat("headroomDb", profile.headroomDb),
                                boostDb = args.getFloat("boostDb", profile.boostDb),
                                bass = args.getFloat("bass", profile.bass),
                                virtualizer = args.getFloat("virtualizer", profile.virtualizer),
                                balance = args.getFloat("balance", profile.balance),
                                preset = args.getString("preset") ?: profile.preset,
                            )
                            args.getFloatArray("gains")?.let { gains ->
                                if (gains.isNotEmpty()) {
                                    incoming = incoming.copy(
                                        gains = List(EQ_BANDS) { index ->
                                            gains.getOrElse(index) { 0f }
                                        },
                                    )
                                }
                            }
                            profile = incoming.cleaned()
                            saveEffectState()
                            applyEqualizer()
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, eqExtras())
                            )
                        }
                        CMD_EQ_GET -> return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, eqExtras())
                        )
                        CMD_SLEEP_SET -> {
                            when (args.getString("mode")) {
                                MODE_TIME -> startSleepTime(
                                    hours = args.getInt("hours", 0),
                                    minutes = args.getInt("minutes", 30),
                                    waitTillEnd = args.getBoolean("waitTillEnd", false),
                                    fade = args.getBoolean("fade", true),
                                )
                                MODE_TRACKS -> startSleepTracks(
                                    tracks = args.getInt("tracks", 10),
                                    fade = args.getBoolean("fade", true),
                                )
                                else -> cancelSleep()
                            }
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, sleepExtras())
                            )
                        }
                        CMD_SLEEP_GET -> return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, sleepExtras())
                        )
                        CMD_SLEEP_CANCEL -> {
                            cancelSleep()
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, sleepExtras())
                            )
                        }
                        CMD_AB_SET -> {
                            val start = args.getLong("startMs", -1L)
                            val end = args.getLong("endMs", -1L)
                            if (start >= 0L) {
                                setAbLoop(start, end)
                            } else {
                                clearAbLoop()
                            }
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, abExtras())
                            )
                        }
                        CMD_AB_GET -> return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, abExtras())
                        )
                        CMD_AB_CLEAR -> {
                            clearAbLoop()
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, abExtras())
                            )
                        }
                        CMD_STOP_AFTER_SET -> {
                            setStopAfter(args.getBoolean("enabled", false))
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, stopAfterExtras())
                            )
                        }
                        CMD_STOP_AFTER_GET -> return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, stopAfterExtras())
                        )
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        settingsListenerRegistered = true
        loadOverlayLyrics(exo.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L)
        syncOverlay()
        WidgetRenderer.update(this, exo)
        if (exo.isPlaying) startWidgetTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_FLOATING_REFRESH) {
            syncOverlay()
        }
        return result
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun currentMediaId(): Long =
        player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L

    private fun syncOverlay() {
        val wanted = settingsPrefs.getBoolean("floating_lyrics", false) &&
            Settings.canDrawOverlays(this)
        if (!wanted) {
            overlay?.hide()
            overlay = null
            mainHandler.removeCallbacks(overlayTicker)
            overlayLyricsJob?.cancel()
            overlayLyricsJob = null
            overlayLyrics = null
            overlayLyricsOffset = 0
            overlayLyricsMediaId = -1L
            return
        }
        if (overlay == null) {
            overlay = FloatingLyricsOverlay(this, overlayActions)
            loadOverlayLyrics(currentMediaId())
            overlay?.show(overlayState())
            mainHandler.removeCallbacks(overlayTicker)
            mainHandler.post(overlayTicker)
        } else {
            overlay?.applyState(overlayState())
        }
    }

    private fun loadOverlayLyrics(mediaId: Long) {
        if (mediaId == overlayLyricsMediaId && overlayLyricsJob != null) return
        overlayLyricsJob?.cancel()
        overlayLyricsJob = null
        overlayLyrics = null
        overlayLyricsOffset = 0
        overlayLyricsMediaId = mediaId
        if (mediaId <= 0) {
            overlay?.applyState(overlayState())
            return
        }
        overlayLyricsJob = overlayScope.launch {
            LiltDatabase.get(this@PlaybackService).lyricsDao().observe(mediaId).collect { entry ->
                overlayLyrics = entry?.text
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::parseLrc)
                overlayLyricsOffset = entry?.offsetMs ?: 0
                overlay?.applyState(overlayState())
            }
        }
    }

    private fun maybeFetchOnlineLyrics(mediaItem: MediaItem?) {
        val item = mediaItem ?: return
        val mediaId = item.mediaId.toLongOrNull() ?: return
        if (mediaId <= 0L) return
        if (player?.isPlaying != true) return
        if (!settingsPrefs.getBoolean("online_lyrics", true)) return
        val title = item.mediaMetadata.title?.toString().orEmpty()
        if (title.isBlank()) return
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val album = item.mediaMetadata.albumTitle?.toString().orEmpty()
        val duration = item.mediaMetadata.durationMs
            ?: player?.duration?.takeIf { it > 0L }
            ?: 0L
        lyricsFetchJob?.cancel()
        lyricsFetchJob = overlayScope.launch {
            OnlineLyricsEngine.fetchLrclib(
                context = this@PlaybackService,
                mediaId = mediaId,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
            )
        }
    }

    private fun overlayState(): FloatingLyricsState {
        val exo = player
        val item = exo?.currentMediaItem
        val duration = exo?.duration?.takeIf { it > 0L } ?: 0L
        val position = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        return FloatingLyricsState(
            mediaId = item?.mediaId?.toLongOrNull() ?: -1L,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = exo?.isPlaying == true,
            positionMs = position,
            durationMs = duration,
            lyrics = overlayLyrics,
            offsetMs = overlayLyricsOffset,
            accent = accentArgb(settingsPrefs.getString("accent", "ember") ?: "ember"),
            opacity = settingsPrefs.getFloat("lyrics_opacity", 1f).coerceIn(0.35f, 1f),
            fontSp = clampFloatingFontSize(
                settingsPrefs.getFloat("floating_font_size", 26f),
            ),
            locked = settingsPrefs.getBoolean("floating_lyrics_locked", false),
            chinese = settingsPrefs.getString("language", "en") == "zh",
            hasPrevious = exo?.hasPreviousMediaItem() == true,
            hasNext = exo?.hasNextMediaItem() == true,
        )
    }

    private fun startWidgetTicker() {
        mainHandler.removeCallbacks(widgetTicker)
        mainHandler.post(widgetTicker)
    }

    private fun stopWidgetTicker() {
        mainHandler.removeCallbacks(widgetTicker)
    }

    private inner class FadePlayer(inner: Player) : ForwardingPlayer(inner) {
        override fun play() {
            val wasPlaying = isPlaying
            if (!wasPlaying) {
                cancelFade()
                val duration = settingsPrefs.getInt("fade_in_ms", 0).coerceIn(0, FADE_MAX_MS)
                if (duration > 0) {
                    setVolumeNow(0f)
                    super.play()
                    rampVolume(0f, 1f, duration.toLong())
                } else {
                    setVolumeNow(1f)
                    super.play()
                }
            } else {
                super.play()
            }
        }

        override fun pause() {
            if (isPlaying) {
                val duration = settingsPrefs.getInt("fade_out_ms", 0).coerceIn(0, FADE_MAX_MS)
                if (duration > 0) {
                    rampVolume(volume.coerceIn(0f, 1f), 0f, duration.toLong()) {
                        pauseNow()
                        setVolumeNow(1f)
                    }
                } else {
                    cancelFade()
                    setVolumeNow(1f)
                    pauseNow()
                }
            } else {
                super.pause()
            }
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else pause()
        }

        fun setVolumeNow(value: Float) {
            super.setVolume(value.coerceIn(0f, 1f))
        }

        fun pauseNow() {
            super.pause()
        }
    }

    /** Ramps the output volume on the main looper in 50 ms steps. */
    private fun rampVolume(from: Float, to: Float, durationMs: Long, end: (() -> Unit)? = null) {
        cancelFade()
        val target = playback ?: return
        if (durationMs <= 0) {
            target.setVolumeNow(to)
            end?.invoke()
            return
        }
        val started = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.elapsedRealtime() - started).toFloat()
                val fraction = (elapsed / durationMs).coerceIn(0f, 1f)
                target.setVolumeNow(from + (to - from) * fraction)
                if (fraction >= 1f) {
                    fadeRunnable = null
                    end?.invoke()
                } else {
                    mainHandler.postDelayed(this, 50)
                }
            }
        }
        fadeRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelFade() {
        fadeRunnable?.let { mainHandler.removeCallbacks(it) }
        fadeRunnable = null
    }

    private fun startSleepTime(hours: Int, minutes: Int, waitTillEnd: Boolean, fade: Boolean) {
        val totalMs = (hours.coerceIn(0, 23) * 60 + minutes.coerceIn(0, 59)) * 60_000L
        if (totalMs <= 0) {
            cancelSleep()
            return
        }
        cancelFade()
        playback?.setVolumeNow(1f)
        sleep = SleepState(
            mode = MODE_TIME,
            deadline = System.currentTimeMillis() + totalMs,
            totalMs = totalMs,
            waitTillEnd = waitTillEnd,
            fade = fade,
        )
        startSleepTicker()
    }

    private fun startSleepTracks(tracks: Int, fade: Boolean) {
        cancelFade()
        playback?.setVolumeNow(1f)
        sleep = SleepState(
            mode = MODE_TRACKS,
            tracksLeft = tracks.coerceIn(1, 99),
            fade = fade,
        )
        startSleepTicker()
    }

    private fun cancelSleep() {
        sleep = SleepState()
        stopSleepTicker()
        cancelFade()
        playback?.setVolumeNow(1f)
    }

    private fun stopForSleep() {
        val exo = player ?: return
        val shouldPause = exo.isPlaying || exo.playWhenReady
        val shouldFade = sleep.fade && exo.isPlaying
        sleep = sleep.copy(mode = MODE_OFF, pendingStop = false)
        stopSleepTicker()
        cancelFade()
        if (shouldPause) {
            if (shouldFade) {
                rampVolume(exo.volume.coerceIn(0f, 1f), 0f, SLEEP_FADE_MS) {
                    exo.volume = 1f
                    exo.pause()
                }
            } else {
                exo.pause()
                exo.volume = 1f
            }
        } else {
            exo.volume = 1f
        }
    }

    private val sleepTicker = object : Runnable {
        override fun run() {
            if (sleep.mode == MODE_OFF) return
            val exo = player ?: return
            var state = sleep
            if (state.mode == MODE_TIME && !state.pendingStop &&
                System.currentTimeMillis() >= state.deadline
            ) {
                if (state.waitTillEnd && exo.isPlaying) {
                    state = state.copy(pendingStop = true)
                    sleep = state
                } else {
                    sleep = state.copy(pendingStop = true)
                    stopForSleep()
                    return
                }
            }
            if (state.mode == MODE_TRACKS && state.tracksLeft <= 1 && !state.pendingStop) {
                val duration = exo.duration
                val position = exo.currentPosition
                if (duration > 0) {
                    val fadeMs = if (state.fade) SLEEP_FADE_MS else 250L
                    if (duration - position <= fadeMs + 400) {
                        sleep = state.copy(pendingStop = true)
                        stopForSleep()
                        return
                    }
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun startSleepTicker() {
        if (sleep.mode == MODE_OFF) return
        mainHandler.removeCallbacks(sleepTicker)
        mainHandler.post(sleepTicker)
    }

    private fun stopSleepTicker() {
        mainHandler.removeCallbacks(sleepTicker)
    }

    private fun sleepExtras(): Bundle {
        val state = sleep
        val remaining = if (state.mode == MODE_TIME) {
            (state.deadline - System.currentTimeMillis()).coerceAtLeast(0)
        } else {
            0L
        }
        return Bundle().apply {
            putString("mode", state.mode)
            putLong("remainingMs", remaining)
            putLong("totalMs", state.totalMs)
            putInt("tracksLeft", state.tracksLeft)
            putBoolean("waitTillEnd", state.waitTillEnd)
            putBoolean("fade", state.fade)
            putBoolean("pendingStop", state.pendingStop)
        }
    }

    private fun setAbLoop(startMs: Long, endMs: Long) {
        abStartMs = startMs.coerceAtLeast(0L)
        abEndMs = if (endMs > abStartMs) endMs else -1L
        abLastJumpAt = 0L
        mainHandler.removeCallbacks(abTicker)
        if (abEndMs > abStartMs) mainHandler.post(abTicker)
    }

    private fun clearAbLoop() {
        abStartMs = -1L
        abEndMs = -1L
        mainHandler.removeCallbacks(abTicker)
    }

    private fun abExtras(): Bundle = Bundle().apply {
        putLong("startMs", abStartMs)
        putLong("endMs", abEndMs)
    }

    private fun setStopAfter(enabled: Boolean) {
        stopAfterCurrent = enabled
        mainHandler.removeCallbacks(stopAfterTicker)
        if (enabled && player?.isPlaying == true) {
            mainHandler.post(stopAfterTicker)
        }
    }

    private fun startStopAfterTicker() {
        if (!stopAfterCurrent) return
        mainHandler.removeCallbacks(stopAfterTicker)
        mainHandler.post(stopAfterTicker)
    }

    private fun stopAfterExtras(): Bundle = Bundle().apply {
        putBoolean("enabled", stopAfterCurrent)
    }

    private fun loadEffectState() {
        val gains = prefs.getString("gains", null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EQ_BANDS }
            ?: List(EQ_BANDS) { 0f }
        val stored = EqProfile(
            enabled = prefs.getBoolean("enabled", true),
            gains = gains,
            preampMode = PreampMode.fromId(prefs.getString("preamp_mode", "off")),
            headroomDb = prefs.getFloat("headroom_db", 6f),
            boostDb = prefs.getFloat("boost_db", 2f),
            bass = prefs.getFloat("bass", 0f),
            virtualizer = prefs.getFloat("virtualizer", 0f),
            balance = prefs.getFloat("balance", 100f),
            preset = prefs.getString("preset", "Flat") ?: "Flat",
        ).cleaned()
        perDevice = prefs.getBoolean("per_device", true)
        refreshOutputDevice(loadProfile = false)
        profile = stored
        if (perDevice) {
            EqProfile.decode(prefs.getString("profile_$deviceKey", null))?.let {
                profile = it
            }
        }
    }

    private fun saveEffectState() {
        val editor = prefs.edit()
            .putBoolean("enabled", profile.enabled)
            .putString("gains", profile.gains.joinToString(","))
            .putString("preamp_mode", profile.preampMode.id)
            .putFloat("headroom_db", profile.headroomDb)
            .putFloat("boost_db", profile.boostDb)
            .putFloat("bass", profile.bass)
            .putFloat("virtualizer", profile.virtualizer)
            .putFloat("balance", profile.balance)
            .putString("preset", profile.preset)
            .putBoolean("per_device", perDevice)
        if (perDevice) {
            editor.putString("profile_$deviceKey", profile.encode())
        }
        editor.apply()
    }

    private fun refreshOutputDevice(loadProfile: Boolean) {
        val found = findOutputDevice()
        deviceName = found.second
        if (found.first == deviceKey) return
        deviceKey = found.first
        if (loadProfile && perDevice) {
            val stored = EqProfile.decode(prefs.getString("profile_$deviceKey", null))
            if (stored != null) {
                profile = stored
            } else {
                saveEffectState()
            }
            applyEqualizer()
        }
    }

    private fun findOutputDevice(): Pair<String, String> {
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())
        val priorities = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        var device: AudioDeviceInfo? = null
        for (type in priorities) {
            device = devices.firstOrNull { it.type == type && it.isSink }
            if (device != null) break
        }
        val key = when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            -> "bluetooth"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            -> "usb"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            -> "wired"
            AudioDeviceInfo.TYPE_HEARING_AID -> "hearing"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            else -> "default"
        }
        val name = when (key) {
            "bluetooth" -> "Bluetooth"
            "usb" -> "USB audio"
            "wired" -> "Wired headset"
            "hearing" -> "Hearing aid"
            "speaker" -> "Phone speaker"
            else -> "System output"
        }
        return key to name
    }

    private fun eqExtras(): Bundle = Bundle().apply {
        putBoolean("enabled", profile.enabled)
        putFloatArray("gains", profile.gains.toFloatArray())
        putString("preampMode", profile.preampMode.id)
        putFloat("headroomDb", profile.headroomDb)
        putFloat("boostDb", profile.boostDb)
        putFloat("bass", profile.bass)
        putFloat("virtualizer", profile.virtualizer)
        putFloat("balance", profile.balance)
        putString("preset", profile.preset)
        putBoolean("perDevice", perDevice)
        putString("deviceName", deviceName)
        putBoolean("virtualizerAvailable", virtualizerAvailable)
    }

    @Suppress("DEPRECATION")
    private fun attachEqualizer(audioSession: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    EQ_BANDS,
                    false,
                    0,
                    false,
                    0,
                    false,
                ).build()
                dynamics = DynamicsProcessing(0, audioSession, config).apply {
                    setEnabled(profile.enabled)
                }
            }
        } else {
            runCatching {
                legacyEq = Equalizer(0, audioSession).apply {
                    setEnabled(profile.enabled)
                }
            }
        }
        virtualizerAvailable = runCatching {
            val effect = Virtualizer(0, audioSession)
            if (effect.strengthSupported) {
                effect.setEnabled(false)
                virtualizer = effect
                true
            } else {
                effect.release()
                false
            }
        }.getOrDefault(false)
        applyEqualizer()
    }

    private fun applyEqualizer() {
        val current = profile
        val preamp = current.preampDb()
        val (leftBalance, rightBalance) = balanceOffsetsDb(current.balance)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val effect = dynamics ?: return
            runCatching {
                effect.setEnabled(current.enabled)
                if (!current.enabled) return@runCatching
                val offsets = floatArrayOf(leftBalance, rightBalance)
                for (channel in 0..1) {
                    val eq = DynamicsProcessing.Eq(true, true, EQ_BANDS)
                    EQ_FREQUENCIES.forEachIndexed { index, frequency ->
                        val gain = (
                            current.gains[index] +
                                preamp +
                                bassBoostDb(frequency, current.bass) +
                                offsets[channel]
                            ).coerceIn(-12f, 12f)
                        eq.setBand(
                            index,
                            DynamicsProcessing.EqBand(true, frequency, gain),
                        )
                    }
                    effect.setPreEqByChannelIndex(channel, eq)
                }
            }
        } else {
            val effect = legacyEq ?: return
            runCatching {
                effect.setEnabled(current.enabled)
                if (!current.enabled) return@runCatching
                val levels = effect.bandLevelRange
                val min = levels.getOrElse(0) { -1500 }.toFloat()
                val max = levels.getOrElse(1) { 1500 }.toFloat()
                val count = effect.numberOfBands.toInt()
                for (band in 0 until count) {
                    val frequency = effect.getCenterFreq(band.toShort()) / 1000
                    val nearest = EQ_FREQUENCIES.indices.minByOrNull {
                        kotlin.math.abs(EQ_FREQUENCIES[it] - frequency)
                    } ?: 0
                    val gain = current.gains[nearest] +
                        preamp +
                        bassBoostDb(EQ_FREQUENCIES[nearest], current.bass)
                    val millibels = (gain * 100).coerceIn(min, max).toInt().toShort()
                    effect.setBandLevel(band.toShort(), millibels)
                }
            }
        }
        applyVirtualizer()
    }

    @Suppress("DEPRECATION")
    private fun applyVirtualizer() {
        val effect = virtualizer ?: return
        runCatching {
            if (!profile.enabled || profile.virtualizer <= 0.01f) {
                effect.setEnabled(false)
                return@runCatching
            }
            effect.setStrength(
                (profile.virtualizer * 1000f).toInt().coerceIn(0, 1000).toShort(),
            )
            effect.setEnabled(true)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelFade()
        stopSleepTicker()
        stopWidgetTicker()
        mainHandler.removeCallbacks(abTicker)
        mainHandler.removeCallbacks(stopAfterTicker)
        mainHandler.removeCallbacks(overlayTicker)
        overlayLyricsJob?.cancel()
        overlayLyricsJob = null
        overlay?.hide()
        overlay = null
        overlayScope.cancel()
        if (settingsListenerRegistered) {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            settingsListenerRegistered = false
        }
        WidgetRenderer.idle(this)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        session?.release()
        session = null
        dynamics?.release()
        dynamics = null
        legacyEq?.release()
        legacyEq = null
        virtualizer?.release()
        virtualizer = null
        player?.release()
        player = null
        playback = null
        super.onDestroy()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private data class SleepState(
        val mode: String = MODE_OFF,
        val deadline: Long = 0L,
        val totalMs: Long = 0L,
        val tracksLeft: Int = 0,
        val waitTillEnd: Boolean = false,
        val fade: Boolean = false,
        val pendingStop: Boolean = false,
    )

    companion object {
        private const val FADE_MAX_MS = 4000
        private const val SLEEP_FADE_MS = 4_000L
        private const val MODE_OFF = "off"
        private const val MODE_TIME = "time"
        private const val MODE_TRACKS = "tracks"
        const val CMD_EQ_SET = "dev.lilt.player.EQ_SET"
        const val CMD_EQ_GET = "dev.lilt.player.EQ_GET"
        const val CMD_SLEEP_SET = "dev.lilt.player.SLEEP_SET"
        const val CMD_SLEEP_GET = "dev.lilt.player.SLEEP_GET"
        const val CMD_SLEEP_CANCEL = "dev.lilt.player.SLEEP_CANCEL"
        const val CMD_AB_SET = "dev.lilt.player.AB_SET"
        const val CMD_AB_GET = "dev.lilt.player.AB_GET"
        const val CMD_AB_CLEAR = "dev.lilt.player.AB_CLEAR"
        const val CMD_STOP_AFTER_SET = "dev.lilt.player.STOP_AFTER_SET"
        const val CMD_STOP_AFTER_GET = "dev.lilt.player.STOP_AFTER_GET"
        const val ACTION_FLOATING_REFRESH = "dev.lilt.player.FLOATING_REFRESH"
    }
}
