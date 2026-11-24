package dev.lilt.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.lilt.player.data.BackupManager
import dev.lilt.player.data.BackupPreview
import dev.lilt.player.data.BookmarkEntity
import dev.lilt.player.data.HistoryEntity
import dev.lilt.player.data.LiltBackup
import dev.lilt.player.data.LiltDatabase
import dev.lilt.player.data.LyricsEntity
import dev.lilt.player.data.LyricsFetchEntity
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.MissingReference
import dev.lilt.player.data.OnlineLyricsClient
import dev.lilt.player.data.OnlineLyricsEngine
import dev.lilt.player.data.RestoreSummary
import dev.lilt.player.data.ScanState
import dev.lilt.player.data.toProfile
import dev.lilt.player.data.SearchHistoryEntity
import dev.lilt.player.data.SettingsStore
import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.SongStateEntity
import dev.lilt.player.library.EQ_PRESETS
import dev.lilt.player.library.EqProfile
import dev.lilt.player.library.LyricsCandidate
import dev.lilt.player.library.LyricsSource
import dev.lilt.player.library.PreampMode
import dev.lilt.player.library.candidatesFromJson
import dev.lilt.player.library.findAiProvider
import dev.lilt.player.library.presetNameFor
import dev.lilt.player.library.queueStart
import dev.lilt.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A play is counted after this share of the track has been heard. */
private const val PLAY_COUNT_PERCENT = 30L

data class PlayerUiState(
    val mediaId: Long = -1,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1,
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val itemCount: Int = 0,
    val index: Int = 0,
)

data class EqUiState(
    val profile: EqProfile = EqProfile(),
    val perDevice: Boolean = true,
    val deviceName: String = "Phone speaker",
    val virtualizerAvailable: Boolean = false,
)

data class SleepUiState(
    val mode: String = "off",
    val remainingMs: Long = 0,
    val totalMs: Long = 0,
    val tracksLeft: Int = 0,
    val waitTillEnd: Boolean = false,
    val fade: Boolean = true,
    val pendingStop: Boolean = false,
) {
    val active: Boolean get() = mode != "off"
}

data class AbLoopUiState(
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    val active: Boolean get() = startMs != null && endMs != null
}

data class AiLyricsUiState(
    val mediaId: Long = -1L,
    val title: String = "",
    val provider: String = "",
    val loading: Boolean = false,
    val text: String = "",
    val error: String = "",
) {
    val visible: Boolean
        get() = mediaId > 0 && (loading || text.isNotBlank() || error.isNotBlank())
}

data class CandidatePickerUiState(
    val mediaId: Long = -1L,
    val title: String = "",
    val candidates: List<LyricsCandidate> = emptyList(),
    val selected: Int = 0,
) {
    val visible: Boolean get() = mediaId > 0 && candidates.isNotEmpty()
}

class LiltViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = LiltDatabase.get(application)
    private val settings = SettingsStore(application)
    private val backupManager by lazy { BackupManager(application) }
    val repository = MusicRepository(application, db, settings)

    val songs: StateFlow<List<SongEntity>> = repository.observeSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allSongs: StateFlow<List<SongEntity>> = repository.observeAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queues = repository.observeQueues()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules = repository.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favoriteIds: StateFlow<Set<Long>> = repository.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val songStates: StateFlow<Map<Long, SongStateEntity>> = repository.observeSongStates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val history: StateFlow<List<HistoryEntity>> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = repository.observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanState: StateFlow<ScanState> = repository.scanState.asStateFlow()

    val language = settings.language
    val theme = settings.theme
    val accent = settings.accent
    val includeOnly = settings.includeOnly
    val queueConflict = settings.queueConflict
    val playbackSpeed = settings.playbackSpeed
    val activeQueueId = settings.activeQueueId
    val songSort = settings.songSort
    val songSortDescending = settings.songSortDescending
    val fadeInMs = settings.fadeInMs
    val fadeOutMs = settings.fadeOutMs
    val sleepHours = settings.sleepHours
    val sleepMinutes = settings.sleepMinutes
    val sleepTracks = settings.sleepTracks
    val sleepWaitTillEnd = settings.sleepWaitTillEnd
    val sleepFade = settings.sleepFade
    val rememberQueuePositions = settings.rememberQueuePositions
    val lyricsFontSize = settings.lyricsFontSize
    val lyricsOpacity = settings.lyricsOpacity
    val floatingLyrics = settings.floatingLyrics
    val floatingLyricsLocked = settings.floatingLyricsLocked
    val floatingFontSize = settings.floatingFontSize
    val onlineLyrics = settings.onlineLyrics
    val aiProvider = settings.aiProvider
    val aiBaseUrl = settings.aiBaseUrl
    val aiModel = settings.aiModel
    val aiKeySaved = settings.aiKeySaved

    private val _aiLyrics = MutableStateFlow(AiLyricsUiState())
    val aiLyrics: StateFlow<AiLyricsUiState> = _aiLyrics.asStateFlow()

    private val _candidates = MutableStateFlow(CandidatePickerUiState())
    val candidates: StateFlow<CandidatePickerUiState> = _candidates.asStateFlow()

    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    val aiModels: StateFlow<List<String>> = _aiModels.asStateFlow()

    private val _aiSettingsBusy = MutableStateFlow(false)
    val aiSettingsBusy: StateFlow<Boolean> = _aiSettingsBusy.asStateFlow()

    private val _aiSettingsStatus = MutableStateFlow("")
    val aiSettingsStatus: StateFlow<String> = _aiSettingsStatus.asStateFlow()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private val _sleep = MutableStateFlow(SleepUiState())
    val sleep: StateFlow<SleepUiState> = _sleep.asStateFlow()

    private val _abLoop = MutableStateFlow(AbLoopUiState())
    val abLoop: StateFlow<AbLoopUiState> = _abLoop.asStateFlow()

    private val _stopAfter = MutableStateFlow(false)
    val stopAfter: StateFlow<Boolean> = _stopAfter.asStateFlow()

    private val _stopAfterNotice = MutableStateFlow(false)
    val stopAfterNotice: StateFlow<Boolean> = _stopAfterNotice.asStateFlow()

    private val _missingFiles = MutableStateFlow<List<MissingReference>>(emptyList())
    val missingFiles: StateFlow<List<MissingReference>> = _missingFiles.asStateFlow()

    private val _eq = MutableStateFlow(EqUiState())
    val eq: StateFlow<EqUiState> = _eq.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var lastRecorded = -1L
    private var countedMediaId = -1L
    private var pendingPlay: (() -> Unit)? = null
    private var lastQueueSaveQueue = -1L
    private var lastQueueSaveIndex = -1
    private var lastQueueSavePosition = -1L
    private var queueSaveGuardUntil = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            countedMediaId = -1L
            _abLoop.value = AbLoopUiState()
            _stopAfter.value = false
            _stopAfterNotice.value = false
            if (SystemClock.elapsedRealtime() >= queueSaveGuardUntil) {
                saveActiveQueuePosition(force = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveActiveQueuePosition(force = true)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (player.currentMediaItemIndex != _player.value.index || player.currentMediaItem != null) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                if (id > 0 && id != lastRecorded && player.isPlaying) {
                    lastRecorded = id
                    viewModelScope.launch { repository.recordHistory(id) }
                }
            }
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(listener)
                    publish(c)
                    c.setPlaybackParameters(PlaybackParameters(settings.playbackSpeed.value))
                    refreshEq(c)
                    refreshSleep(c)
                    refreshAbLoop(c)
                    refreshStopAfter(c)
                    pendingPlay?.invoke()
                    pendingPlay = null
                } catch (_: Exception) {
                    // The service can be unavailable while a device is locked
                    // down; the UI simply stays idle and can retry later.
                }
            },
            ContextCompat.getMainExecutor(app),
        )
        viewModelScope.launch {
            while (isActive) {
                controller?.let {
                    if (it.isPlaying) {
                        publish(it)
                        maybeCountPlay(it)
                    }
                }
                delay(500)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (_sleep.value.active) controller?.let(::refreshSleep)
                if (_stopAfter.value) controller?.let(::refreshStopAfter)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                if (controller?.isPlaying == true) saveActiveQueuePosition()
            }
        }
    }

    private fun saveActiveQueuePosition(force: Boolean = false) {
        if (SystemClock.elapsedRealtime() < queueSaveGuardUntil) return
        val player = controller ?: return
        val queueId = settings.activeQueueId.value
        if (queueId <= 0 || player.mediaItemCount == 0) return
        if (!settings.rememberQueuePositions.value) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = if (player.playbackState == Player.STATE_ENDED) {
            0L
        } else {
            player.currentPosition.coerceAtLeast(0)
        }
        if (!force &&
            queueId == lastQueueSaveQueue &&
            index == lastQueueSaveIndex &&
            kotlin.math.abs(position - lastQueueSavePosition) < 2_000
        ) {
            return
        }
        lastQueueSaveQueue = queueId
        lastQueueSaveIndex = index
        lastQueueSavePosition = position
        viewModelScope.launch {
            repository.saveQueuePosition(queueId, index, position)
        }
    }

    private fun maybeCountPlay(player: Player) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (id <= 0 || id == countedMediaId) return
        val duration = player.duration
        if (duration <= 0) return
        val threshold = (duration * PLAY_COUNT_PERCENT / 100).coerceAtLeast(1L)
        if (player.currentPosition >= threshold) {
            countedMediaId = id
            viewModelScope.launch { repository.recordPlayed(id) }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        _player.value = PlayerUiState(
            mediaId = item?.mediaId?.toLongOrNull() ?: -1L,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            album = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
            albumId = item?.mediaMetadata?.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L,
            artworkUri = item?.mediaMetadata?.artworkUri,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            itemCount = player.mediaItemCount,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
        )
    }

    fun scan() {
        viewModelScope.launch {
            repository.scan()
            refreshMissingFiles()
        }
    }

    fun refreshMissingFiles() {
        viewModelScope.launch {
            _missingFiles.value = repository.missingReferences()
        }
    }

    fun relinkMissing(oldId: Long, newId: Long) {
        viewModelScope.launch {
            repository.relinkMissing(oldId, newId)
            _missingFiles.value = repository.missingReferences()
        }
    }

    fun playSongs(
        songs: List<SongEntity>,
        startIndex: Int,
        queueId: Long? = null,
        startPositionMs: Long = 0L,
    ) {
        val player = controller
        if (player == null) {
            pendingPlay = { playSongs(songs, startIndex, queueId, startPositionMs) }
            return
        }
        if (songs.isEmpty()) return
        if (queueId == null || queueId != settings.activeQueueId.value) {
            saveActiveQueuePosition(force = true)
        }
        if (queueId == null) settings.setActiveQueue(-1) else settings.setActiveQueue(queueId)
        queueSaveGuardUntil = SystemClock.elapsedRealtime() + 1_500
        countedMediaId = -1L
        _stopAfterNotice.value = false
        player.setMediaItems(
            songs.map(::mediaItem),
            startIndex.coerceIn(0, songs.lastIndex),
            startPositionMs.coerceAtLeast(0),
        )
        player.prepare()
        player.play()
        player.setPlaybackParameters(PlaybackParameters(settings.playbackSpeed.value))
        publish(player)
    }

    /** Starts a song at a stored bookmark position. */
    fun playAt(song: SongEntity, positionMs: Long) {
        playSongs(listOf(song), 0, startPositionMs = positionMs)
    }

    fun playQueue(queueId: Long) {
        viewModelScope.launch {
            val queue = repository.queue(queueId) ?: return@launch
            val songs = repository.queueSongs(queueId)
            if (songs.isEmpty()) return@launch
            val start = queueStart(
                savedIndex = queue.lastIndex,
                savedPositionMs = queue.lastPositionMs,
                songCount = songs.size,
                remember = settings.rememberQueuePositions.value,
            )
            playSongs(songs, start.index, queueId, start.positionMs)
            lastQueueSaveQueue = -1
            lastQueueSaveIndex = -1
            lastQueueSavePosition = -1
        }
    }

    /** Plays a queue from an explicit row, ignoring the saved resume position. */
    fun playQueueAt(queueId: Long, index: Int) {
        viewModelScope.launch {
            val songs = repository.queueSongs(queueId)
            if (songs.isEmpty()) return@launch
            playSongs(songs, index.coerceIn(0, songs.lastIndex), queueId)
        }
    }

    fun moveQueueSong(queueId: Long, from: Int, to: Int) {
        viewModelScope.launch { repository.moveQueueSong(queueId, from, to) }
    }

    fun removeQueueSong(queueId: Long, mediaId: Long) {
        viewModelScope.launch { repository.removeQueueSong(queueId, mediaId) }
    }

    fun enqueueNext(song: SongEntity) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        player.addMediaItem(index + 1, mediaItem(song))
    }

    fun addToQueue(song: SongEntity) {
        controller?.addMediaItem(mediaItem(song))
    }

    fun saveCurrentQueue(name: String) {
        val player = controller ?: return
        val ids = (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index).mediaId.toLongOrNull()
        }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        viewModelScope.launch {
            val id = repository.createQueue(
                name,
                ids,
                lastIndex = index,
                lastPositionMs = position,
            )
            settings.setActiveQueue(id)
            lastQueueSaveQueue = -1
            lastQueueSaveIndex = -1
            lastQueueSavePosition = -1
        }
    }

    fun renameQueue(id: Long, name: String) {
        viewModelScope.launch { repository.renameQueue(id, name) }
    }

    fun deleteQueue(id: Long) {
        viewModelScope.launch { repository.deleteQueue(id) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { repository.renamePlaylist(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }

    fun addToPlaylist(playlistId: Long, song: SongEntity) {
        viewModelScope.launch { repository.addToPlaylist(playlistId, song.mediaId) }
    }

    fun removeFromPlaylist(playlistId: Long, song: SongEntity) {
        viewModelScope.launch { repository.removeFromPlaylist(playlistId, song.mediaId) }
    }

    fun movePlaylistSong(playlistId: Long, from: Int, to: Int) {
        viewModelScope.launch { repository.movePlaylistSong(playlistId, from, to) }
    }

    fun playlistSongs(id: Long) = repository.observePlaylistSongs(id)
    fun queueSongs(id: Long) = repository.observeQueueSongs(id)

    fun tagOverride(mediaId: Long) = repository.observeTagOverride(mediaId)

    fun saveTagOverride(
        mediaId: Long,
        title: String,
        artist: String,
        album: String,
        year: Int,
        track: Int,
    ) {
        viewModelScope.launch {
            repository.saveTagOverride(mediaId, title, artist, album, year, track)
            refreshCurrentMetadata(mediaId)
        }
    }

    fun resetTagOverride(mediaId: Long) {
        viewModelScope.launch {
            repository.deleteTagOverride(mediaId)
            refreshCurrentMetadata(mediaId)
        }
    }

    /**
     * Re-applies the current listing metadata if the edited song is already
     * playing, without rebuilding the queue. Position is restored after the
     * item replacement.
     */
    private suspend fun refreshCurrentMetadata(mediaId: Long) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || player.currentMediaItem?.mediaId?.toLongOrNull() != mediaId) return
        val song = repository.songWithTags(mediaId) ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        runCatching {
            player.replaceMediaItem(index, mediaItem(song))
            player.seekTo(index, position)
            if (wasPlaying) player.play()
        }
        publish(player)
    }

    fun togglePlayPause() {
        _stopAfterNotice.value = false
        val player = controller
        if (player == null) {
            pendingPlay = { togglePlayPause() }
            return
        }
        if (player.isPlaying) player.pause() else {
            if (player.mediaItemCount == 0 && songs.value.isNotEmpty()) {
                playSongs(songs.value, 0)
            } else {
                player.play()
            }
        }
        publish(player)
    }

    fun next() {
        _stopAfterNotice.value = false
        controller?.seekToNextMediaItem()
    }

    /** The original restarts the current track after ten seconds. */
    fun previous() {
        _stopAfterNotice.value = false
        val player = controller ?: return
        if (player.currentPosition > 10_000L) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val ab = _abLoop.value
        val start = ab.startMs
        val end = ab.endMs
        if (start != null && end != null && (positionMs < start || positionMs > end)) {
            clearAbLoop()
        }
        controller?.seekTo(positionMs)
        controller?.let(::publish)
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        publish(player)
    }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        publish(player)
    }

    fun setSpeed(speed: Float) {
        settings.setPlaybackSpeed(speed)
        controller?.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun setEqEnabled(enabled: Boolean) {
        updateEqProfile(_eq.value.profile.copy(enabled = enabled))
    }

    fun setEqGain(index: Int, gain: Float) {
        val profile = _eq.value.profile
        val gains = profile.gains.toMutableList()
        if (index !in gains.indices) return
        gains[index] = gain.coerceIn(-15f, 15f)
        updateEqProfile(
            profile.copy(gains = gains, preset = presetNameFor(gains)),
        )
    }

    fun setEqPreset(name: String) {
        val profile = _eq.value.profile
        if (!EQ_PRESETS.containsKey(name)) return
        updateEqProfile(profile.withPreset(name))
    }

    fun setEqPreampMode(mode: PreampMode) {
        updateEqProfile(_eq.value.profile.copy(preampMode = mode))
    }

    fun setEqHeadroom(value: Float) {
        updateEqProfile(_eq.value.profile.copy(headroomDb = value))
    }

    fun setEqBoost(value: Float) {
        updateEqProfile(_eq.value.profile.copy(boostDb = value))
    }

    fun setEqBass(value: Float) {
        updateEqProfile(_eq.value.profile.copy(bass = value))
    }

    fun setEqVirtualizer(value: Float) {
        updateEqProfile(_eq.value.profile.copy(virtualizer = value))
    }

    fun setEqBalance(value: Float) {
        updateEqProfile(_eq.value.profile.copy(balance = value))
    }

    fun setEqPerDevice(enabled: Boolean) {
        _eq.value = _eq.value.copy(perDevice = enabled)
        pushEq()
    }

    fun refreshEq() {
        controller?.let(::refreshEq)
    }

    fun markAbStart() {
        val player = controller ?: return
        val start = player.currentPosition.coerceAtLeast(0L)
        _abLoop.value = AbLoopUiState(startMs = start)
        sendAbLoop(player, start, -1L)
    }

    fun markAbEnd() {
        val player = controller ?: return
        val start = _abLoop.value.startMs ?: return
        val end = player.currentPosition.coerceAtLeast(0L)
        if (end <= start + 250L) {
            _abLoop.value = AbLoopUiState(startMs = start)
            return
        }
        _abLoop.value = AbLoopUiState(startMs = start, endMs = end)
        sendAbLoop(player, start, end)
    }

    fun clearAbLoop() {
        if (_abLoop.value.startMs == null && _abLoop.value.endMs == null) return
        _abLoop.value = AbLoopUiState()
        val player = controller ?: return
        player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_AB_CLEAR, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    private fun sendAbLoop(player: MediaController, start: Long, end: Long) {
        val args = Bundle().apply {
            putLong("startMs", start)
            putLong("endMs", end)
        }
        player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_AB_SET, Bundle.EMPTY),
            args,
        )
    }

    private fun refreshAbLoop(player: MediaController) {
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_AB_GET, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching {
                    val extras = future.get().extras
                    val start = extras.getLong("startMs", -1L)
                    val end = extras.getLong("endMs", -1L)
                    _abLoop.value = if (start >= 0L) {
                        AbLoopUiState(
                            startMs = start,
                            endMs = end.takeIf { it > start },
                        )
                    } else {
                        AbLoopUiState()
                    }
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    fun toggleStopAfter() {
        val next = !_stopAfter.value
        _stopAfter.value = next
        if (next) _stopAfterNotice.value = false
        val player = controller ?: return
        val args = Bundle().apply { putBoolean("enabled", next) }
        player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_STOP_AFTER_SET, Bundle.EMPTY),
            args,
        )
    }

    private fun refreshStopAfter(player: MediaController) {
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_STOP_AFTER_GET, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching {
                    val enabled = future.get().extras.getBoolean("enabled", false)
                    val wasEnabled = _stopAfter.value
                    _stopAfter.value = enabled
                    if (
                        wasEnabled &&
                        !enabled &&
                        !player.isPlaying &&
                        player.currentPosition <= 1_500L
                    ) {
                        _stopAfterNotice.value = true
                    }
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun updateEqProfile(profile: EqProfile) {
        _eq.value = _eq.value.copy(profile = profile.cleaned())
        pushEq()
    }

    private fun pushEq() {
        val player = controller ?: return
        val state = _eq.value
        val profile = state.profile
        val args = Bundle().apply {
            putBoolean("enabled", profile.enabled)
            putFloatArray("gains", profile.gains.toFloatArray())
            putString("preampMode", profile.preampMode.id)
            putFloat("headroomDb", profile.headroomDb)
            putFloat("boostDb", profile.boostDb)
            putFloat("bass", profile.bass)
            putFloat("virtualizer", profile.virtualizer)
            putFloat("balance", profile.balance)
            putString("preset", profile.preset)
            putBoolean("perDevice", state.perDevice)
        }
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_EQ_SET, Bundle.EMPTY),
            args,
        )
        future.addListener(
            {
                runCatching { applyEqExtras(future.get().extras) }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun refreshEq(player: MediaController) {
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_EQ_GET, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching { applyEqExtras(future.get().extras) }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun applyEqExtras(extras: Bundle?) {
        if (extras == null) return
        val gains = extras.getFloatArray("gains")?.toList()
        val current = _eq.value.profile
        val profile = EqProfile(
            enabled = extras.getBoolean("enabled", current.enabled),
            gains = gains ?: current.gains,
            preset = extras.getString("preset") ?: current.preset,
            preampMode = PreampMode.fromId(
                extras.getString("preampMode") ?: current.preampMode.id
            ),
            headroomDb = extras.getFloat("headroomDb", current.headroomDb),
            boostDb = extras.getFloat("boostDb", current.boostDb),
            bass = extras.getFloat("bass", current.bass),
            virtualizer = extras.getFloat("virtualizer", current.virtualizer),
            balance = extras.getFloat("balance", current.balance),
        ).cleaned()
        _eq.value = EqUiState(
            profile = profile,
            perDevice = extras.getBoolean("perDevice", _eq.value.perDevice),
            deviceName = extras.getString("deviceName") ?: _eq.value.deviceName,
            virtualizerAvailable = extras.getBoolean(
                "virtualizerAvailable",
                _eq.value.virtualizerAvailable,
            ),
        )
    }

    fun startSleepTime(hours: Int, minutes: Int, waitTillEnd: Boolean, fade: Boolean) {
        if (hours <= 0 && minutes <= 0) return
        settings.setSleepDefaults(
            hours = hours,
            minutes = minutes,
            tracks = settings.sleepTracks.value,
            waitTillEnd = waitTillEnd,
            fade = fade,
        )
        sendSleep(
            PlaybackService.CMD_SLEEP_SET,
            Bundle().apply {
                putString("mode", "time")
                putInt("hours", hours)
                putInt("minutes", minutes)
                putBoolean("waitTillEnd", waitTillEnd)
                putBoolean("fade", fade)
            },
        )
    }

    fun startSleepTracks(tracks: Int, fade: Boolean) {
        val count = tracks.coerceIn(1, 99)
        settings.setSleepDefaults(
            hours = settings.sleepHours.value,
            minutes = settings.sleepMinutes.value,
            tracks = count,
            waitTillEnd = false,
            fade = fade,
        )
        sendSleep(
            PlaybackService.CMD_SLEEP_SET,
            Bundle().apply {
                putString("mode", "tracks")
                putInt("tracks", count)
                putBoolean("fade", fade)
            },
        )
    }

    fun cancelSleep() {
        sendSleep(PlaybackService.CMD_SLEEP_CANCEL, Bundle.EMPTY)
    }

    private fun sendSleep(action: String, args: Bundle) {
        val player = controller ?: return
        val future = player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener(
            {
                runCatching { applySleep(future.get().extras) }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun refreshSleep(player: MediaController) {
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP_GET, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching { applySleep(future.get().extras) }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun applySleep(extras: Bundle?) {
        if (extras == null) return
        _sleep.value = SleepUiState(
            mode = extras.getString("mode") ?: "off",
            remainingMs = extras.getLong("remainingMs", 0),
            totalMs = extras.getLong("totalMs", 0),
            tracksLeft = extras.getInt("tracksLeft", 0),
            waitTillEnd = extras.getBoolean("waitTillEnd", false),
            fade = extras.getBoolean("fade", true),
            pendingStop = extras.getBoolean("pendingStop", false),
        )
    }

    fun setFadeInMs(value: Int) = settings.setFadeInMs(value)
    fun setFadeOutMs(value: Int) = settings.setFadeOutMs(value)

    fun setLanguage(value: String) = settings.setLanguage(value)
    fun setTheme(value: String) = settings.setTheme(value)
    fun setAccent(value: String) = settings.setAccent(value)
    fun setIncludeOnly(value: Boolean) = settings.setIncludeOnly(value)
    fun setQueueConflict(value: String) = settings.setQueueConflict(value)
    fun setSongSort(value: String) = settings.setSongSort(value)
    fun setSongSortDescending(value: Boolean) = settings.setSongSortDescending(value)
    fun setRememberQueuePositions(value: Boolean) = settings.setRememberQueuePositions(value)
    fun setLyricsFontSize(value: Float) = settings.setLyricsFontSize(value)
    fun setLyricsOpacity(value: Float) = settings.setLyricsOpacity(value)

    fun setFloatingLyrics(value: Boolean) {
        settings.setFloatingLyrics(value)
        refreshFloatingOverlay()
    }

    fun setFloatingLyricsLocked(value: Boolean) {
        settings.setFloatingLyricsLocked(value)
        refreshFloatingOverlay()
    }

    fun setFloatingFontSize(value: Float) = settings.setFloatingFontSize(value)

    suspend fun exportBackup(): String = backupManager.export(settings)

    fun previewBackup(text: String): BackupPreview = LiltBackup.decode(text).preview()

    /** Replaces user-owned data and applies the restored settings to the running player. */
    suspend fun restoreBackup(text: String): RestoreSummary {
        val backup = withContext(Dispatchers.IO) { LiltBackup.decode(text) }
        val result = backupManager.restore(backup, settings)
        result.eq?.let { eq ->
            _eq.value = _eq.value.copy(
                profile = eq.toProfile(),
                perDevice = eq.perDevice,
            )
            pushEq()
        }
        controller?.setPlaybackParameters(PlaybackParameters(settings.playbackSpeed.value))
        refreshFloatingOverlay()
        _missingFiles.value = repository.missingReferences()
        return result.summary
    }

    /** Asks the playback service to add or remove the overlay from current preferences. */
    fun refreshFloatingOverlay() {
        runCatching {
            app.startService(
                Intent(app, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_FLOATING_REFRESH),
            )
        }
    }

    fun toggleFavorite(mediaId: Long) {
        if (mediaId <= 0) return
        viewModelScope.launch { repository.toggleFavorite(mediaId) }
    }

    fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean) {
        val ids = mediaIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.setFavorite(ids, favorite) }
    }

    fun addSongsToQueue(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        controller?.addMediaItems(songs.map(::mediaItem))
    }

    fun addSongsToPlaylist(playlistId: Long, songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        viewModelScope.launch { repository.addToPlaylist(playlistId, songs.map { it.mediaId }) }
    }

    fun removeSongsFromPlaylist(playlistId: Long, songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        viewModelScope.launch { repository.removeFromPlaylist(playlistId, songs.map { it.mediaId }) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun addBookmark(positionMs: Long, note: String) {
        val mediaId = _player.value.mediaId
        if (mediaId <= 0) return
        viewModelScope.launch {
            repository.addBookmark(mediaId, positionMs, note)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    fun recordSearch(query: String) {
        viewModelScope.launch { repository.recordSearch(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repository.clearSearchHistory() }
    }

    fun lyricsFor(mediaId: Long): kotlinx.coroutines.flow.Flow<LyricsEntity?> =
        repository.observeLyrics(mediaId)

    fun saveLyrics(mediaId: Long, text: String, offsetMs: Int = 0) {
        viewModelScope.launch { repository.saveLyrics(mediaId, text, offsetMs) }
    }

    fun setLyricsOffset(mediaId: Long, offsetMs: Int) {
        viewModelScope.launch { repository.setLyricsOffset(mediaId, offsetMs) }
    }

    fun deleteLyrics(mediaId: Long) {
        viewModelScope.launch { repository.deleteLyrics(mediaId) }
    }

    fun lyricsFetchFor(mediaId: Long): kotlinx.coroutines.flow.Flow<LyricsFetchEntity?> =
        repository.observeLyricsFetch(mediaId)

    fun retryOnlineLyrics(mediaId: Long) {
        if (!settings.onlineLyrics.value) {
            _aiSettingsStatus.value = "请先在设置中开启在线歌词"
            return
        }
        viewModelScope.launch {
            val song = repository.songWithTags(mediaId) ?: return@launch
            repository.setLyricsAutoFetch(mediaId, true)
            repository.clearLyricsFetchState(mediaId)
            OnlineLyricsEngine.fetchLrclib(
                context = app,
                mediaId = mediaId,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                force = true,
            )
        }
    }

    fun openCandidates(mediaId: Long) {
        viewModelScope.launch {
            val song = repository.songWithTags(mediaId) ?: return@launch
            var list = candidatesFromJson(repository.lyricsFetchState(mediaId)?.candidates)
            if (list.isEmpty()) {
                if (!settings.onlineLyrics.value) {
                    _aiSettingsStatus.value = "请先在设置中开启在线歌词"
                    return@launch
                }
                repository.clearLyricsFetchState(mediaId)
                OnlineLyricsEngine.fetchLrclib(
                    context = app,
                    mediaId = mediaId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    force = true,
                )
                list = candidatesFromJson(repository.lyricsFetchState(mediaId)?.candidates)
            }
            if (list.isEmpty()) {
                _aiSettingsStatus.value = "没有找到可选择的歌词版本"
            } else {
                _candidates.value = CandidatePickerUiState(
                    mediaId = mediaId,
                    title = song.title,
                    candidates = list,
                    selected = 0,
                )
            }
        }
    }

    fun selectCandidate(index: Int) {
        val state = _candidates.value
        if (index in state.candidates.indices) {
            _candidates.value = state.copy(selected = index)
        }
    }

    fun saveSelectedCandidate() {
        val state = _candidates.value
        val candidate = state.candidates.getOrNull(state.selected) ?: return
        val text = candidate.record.text() ?: return
        viewModelScope.launch {
            repository.saveFetchedLyrics(
                mediaId = state.mediaId,
                text = text,
                source = LyricsSource.LRCLIB,
            )
            _candidates.value = CandidatePickerUiState()
        }
    }

    fun dismissCandidates() {
        _candidates.value = CandidatePickerUiState()
    }

    fun startAiLyrics(mediaId: Long) {
        if (!settings.onlineLyrics.value) {
            _aiLyrics.value = AiLyricsUiState(
                mediaId = mediaId,
                error = "请先在设置中开启在线歌词",
            )
            return
        }
        viewModelScope.launch {
            val song = repository.songWithTags(mediaId) ?: return@launch
            val config = settings.aiConfig()
            if (config == null) {
                _aiLyrics.value = AiLyricsUiState(
                    mediaId = mediaId,
                    title = song.title,
                    error = "请先在设置中配置 AI 供应商、模型和 API Key",
                )
                return@launch
            }
            val provider = findAiProvider(config.provider).name
            _aiLyrics.value = AiLyricsUiState(
                mediaId = mediaId,
                title = song.title,
                provider = provider,
                loading = true,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    OnlineLyricsClient.fetchAiLyrics(
                        config = config,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                    )
                }
            }
            val text = result.getOrNull()
            _aiLyrics.value = when {
                result.isFailure -> AiLyricsUiState(
                    mediaId = mediaId,
                    title = song.title,
                    provider = provider,
                    error = result.exceptionOrNull()?.message ?: "AI 请求失败",
                )
                text == null -> AiLyricsUiState(
                    mediaId = mediaId,
                    title = song.title,
                    provider = provider,
                    error = "AI 没有找到可靠的歌词",
                )
                else -> AiLyricsUiState(
                    mediaId = mediaId,
                    title = song.title,
                    provider = provider,
                    text = text,
                )
            }
        }
    }

    fun saveAiLyrics(text: String) {
        val state = _aiLyrics.value
        if (state.mediaId <= 0 || text.isBlank()) return
        viewModelScope.launch {
            repository.saveFetchedLyrics(
                mediaId = state.mediaId,
                text = text,
                source = LyricsSource.AI,
            )
            _aiLyrics.value = AiLyricsUiState()
        }
    }

    fun dismissAiLyrics() {
        _aiLyrics.value = AiLyricsUiState()
    }

    fun setOnlineLyrics(value: Boolean) {
        settings.setOnlineLyrics(value)
    }

    fun setAiProvider(id: String) {
        settings.setAiProvider(id)
        _aiModels.value = emptyList()
        _aiSettingsStatus.value = ""
    }

    fun setAiBaseUrl(value: String) {
        settings.setAiBaseUrl(value)
        _aiModels.value = emptyList()
    }

    fun setAiModel(value: String) {
        settings.setAiModel(value)
    }

    fun saveAiKey(value: String) {
        val ok = settings.saveAiKey(value)
        _aiSettingsStatus.value = if (ok) "API Key 已保存" else "API Key 保存失败"
    }

    fun clearAiKey() {
        settings.clearAiKey()
        _aiSettingsStatus.value = "API Key 已移除"
    }

    fun fetchAiModels() {
        if (_aiSettingsBusy.value) return
        val config = settings.aiConfig()
        if (config == null) {
            _aiSettingsStatus.value = "请先填写接口地址、模型和 API Key"
            return
        }
        viewModelScope.launch {
            _aiSettingsBusy.value = true
            _aiSettingsStatus.value = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { OnlineLyricsClient.fetchModels(config) }
            }
            _aiSettingsBusy.value = false
            result.onSuccess { models ->
                _aiModels.value = models
                _aiSettingsStatus.value = "${models.size} 个可用模型"
            }.onFailure { error ->
                _aiSettingsStatus.value = error.message ?: "获取模型失败"
            }
        }
    }

    fun testAiConnection() {
        if (_aiSettingsBusy.value) return
        val config = settings.aiConfig()
        if (config == null) {
            _aiSettingsStatus.value = "请先填写接口地址、模型和 API Key"
            return
        }
        viewModelScope.launch {
            _aiSettingsBusy.value = true
            _aiSettingsStatus.value = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { OnlineLyricsClient.testAi(config) }
            }
            _aiSettingsBusy.value = false
            _aiSettingsStatus.value = result.getOrElse {
                it.message ?: "连接失败"
            }
        }
    }

    fun clearLyricsFetchCache() {
        viewModelScope.launch {
            OnlineLyricsEngine.clearAll(app)
            _aiSettingsStatus.value = "歌词获取缓存已清理"
        }
    }

    fun setFolderRule(path: String, kind: String, on: Boolean) {
        viewModelScope.launch { repository.setRule(path, kind, on) }
    }

    fun exportQueue(queueId: Long, uri: Uri) {
        viewModelScope.launch {
            val songs = repository.queueSongs(queueId)
            val text = dev.lilt.player.library.M3u.write(songs)
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                }
            }
        }
    }

    fun importQueue(uri: Uri, name: String) {
        viewModelScope.launch {
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@launch
            val songs = repository.resolveTargets(dev.lilt.player.library.M3u.parseTargets(text))
            if (songs.isNotEmpty()) repository.createQueue(name, songs.map { it.mediaId })
        }
    }

    fun exportPlaylist(playlistId: Long, uri: Uri) {
        viewModelScope.launch {
            val songs = repository.playlistSongs(playlistId)
            val text = dev.lilt.player.library.M3u.write(songs)
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                }
            }
        }
    }

    fun importPlaylist(uri: Uri, name: String) {
        viewModelScope.launch {
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@launch
            val songs = repository.resolveTargets(dev.lilt.player.library.M3u.parseTargets(text))
            if (songs.isNotEmpty()) {
                val id = repository.createPlaylist(name)
                songs.forEach { repository.addToPlaylist(id, it.mediaId) }
            }
        }
    }

    private fun mediaItem(song: SongEntity): MediaItem = MediaItem.Builder()
        .setMediaId(song.mediaId.toString())
        .setUri(song.contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setDurationMs(song.durationMs)
                .setArtworkUri(MusicRepository.albumArtUri(song.albumId))
                .build()
        )
        .build()

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { if (it.isDone && !it.isCancelled) runCatching { it.get().release() } }
        controller = null
        controllerFuture = null
        super.onCleared()
    }

}
