package dev.lilt.player.playback

import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * One foreground media session owns playback, the notification and the
 * lock-screen controls. The activity only talks to it through a
 * [androidx.media3.session.MediaController].
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var dynamics: DynamicsProcessing? = null
    private var legacyEq: Equalizer? = null
    private val prefs by lazy { getSharedPreferences("lilt_effects", MODE_PRIVATE) }
    private var gains = FloatArray(EQ_BANDS)
    private var eqEnabled = true

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
        attachEqualizer(audioSession)
        session = MediaSession.Builder(this, exo)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_EQ_SET, Bundle.EMPTY))
                        .add(SessionCommand(CMD_EQ_GET, Bundle.EMPTY))
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
                    if (customCommand.customAction == CMD_EQ_SET) {
                        eqEnabled = args.getBoolean("enabled", true)
                        args.getFloatArray("gains")?.let { incoming ->
                            for (index in 0 until minOf(incoming.size, EQ_BANDS)) {
                                gains[index] = incoming[index].coerceIn(-15f, 15f)
                            }
                        }
                        saveEffectState()
                        applyEqualizer()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == CMD_EQ_GET) {
                        val extras = Bundle().apply {
                            putBoolean("enabled", eqEnabled)
                            putFloatArray("gains", gains.copyOf())
                        }
                        return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, extras)
                        )
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    private fun loadEffectState() {
        eqEnabled = prefs.getBoolean("enabled", true)
        val stored = prefs.getString("gains", null) ?: return
        stored.split(',').take(EQ_BANDS).forEachIndexed { index, raw ->
            raw.toFloatOrNull()?.let { gains[index] = it.coerceIn(-15f, 15f) }
        }
    }

    private fun saveEffectState() {
        prefs.edit()
            .putBoolean("enabled", eqEnabled)
            .putString("gains", gains.joinToString(","))
            .apply()
    }

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
                    setEnabled(eqEnabled)
                }
            }
        } else {
            runCatching {
                legacyEq = Equalizer(0, audioSession).apply {
                    setEnabled(eqEnabled)
                }
            }
        }
        applyEqualizer()
    }

    private fun applyEqualizer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val effect = dynamics ?: return
            runCatching {
                effect.setEnabled(eqEnabled)
                val eq = DynamicsProcessing.Eq(true, true, EQ_BANDS)
                EQ_FREQUENCIES.forEachIndexed { index, frequency ->
                    eq.setBand(
                        index,
                        DynamicsProcessing.EqBand(
                            eqEnabled,
                            frequency,
                            if (eqEnabled) gains[index] else 0f,
                        ),
                    )
                }
                effect.setPreEqAllChannelsTo(eq)
            }
        } else {
            val effect = legacyEq ?: return
            runCatching {
                effect.setEnabled(eqEnabled)
                val levels = effect.bandLevelRange
                val min = levels.getOrElse(0) { -1500 }.toFloat()
                val max = levels.getOrElse(1) { 1500 }.toFloat()
                val count = effect.numberOfBands.toInt()
                for (band in 0 until count) {
                    val frequency = effect.getCenterFreq(band.toShort()) / 1000
                    val nearest = EQ_FREQUENCIES.indices.minByOrNull {
                        kotlin.math.abs(EQ_FREQUENCIES[it] - frequency)
                    } ?: 0
                    val millibels = (gains[nearest] * 100).coerceIn(min, max).toInt().toShort()
                    effect.setBandLevel(band.toShort(), millibels)
                }
            }
        }
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
        session?.release()
        session = null
        dynamics?.release()
        dynamics = null
        legacyEq?.release()
        legacyEq = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    companion object {
        private const val EQ_BANDS = 10
        private val EQ_FREQUENCIES = floatArrayOf(
            31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )
        const val CMD_EQ_SET = "dev.lilt.player.EQ_SET"
        const val CMD_EQ_GET = "dev.lilt.player.EQ_GET"
    }
}
