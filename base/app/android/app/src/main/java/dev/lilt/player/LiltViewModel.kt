package dev.lilt.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
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
import dev.lilt.player.data.LiltDatabase
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.ScanState
import dev.lilt.player.data.SettingsStore
import dev.lilt.player.data.SongEntity
import dev.lilt.player.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val enabled: Boolean = true,
    val gains: List<Float> = List(10) { 0f },
)

class LiltViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val db = LiltDatabase.get(application)
    private val settings = SettingsStore(application)
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
    val scanState: StateFlow<ScanState> = repository.scanState.asStateFlow()

    val language = settings.language
    val theme = settings.theme
    val accent = settings.accent
    val includeOnly = settings.includeOnly
    val queueConflict = settings.queueConflict
    val playbackSpeed = settings.playbackSpeed
    val activeQueueId = settings.activeQueueId

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private val _sleepDeadline = MutableStateFlow<Long?>(null)
    val sleepDeadline: StateFlow<Long?> = _sleepDeadline.asStateFlow()

    private val _eq = MutableStateFlow(EqUiState())
    val eq: StateFlow<EqUiState> = _eq.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var sleepJob: Job? = null
    private var lastRecorded = -1L
    private var countedMediaId = -1L
    private var pendingPlay: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            countedMediaId = -1L
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
        viewModelScope.launch { repository.scan() }
    }

    fun playSongs(songs: List<SongEntity>, startIndex: Int, queueId: Long? = null) {
        val player = controller
        if (player == null) {
            pendingPlay = { playSongs(songs, startIndex, queueId) }
            return
        }
        if (songs.isEmpty()) return
        if (queueId == null) settings.setActiveQueue(-1) else settings.setActiveQueue(queueId)
        countedMediaId = -1L
        player.setMediaItems(songs.map(::mediaItem), startIndex.coerceIn(0, songs.lastIndex), 0L)
        player.prepare()
        player.play()
        player.setPlaybackParameters(PlaybackParameters(settings.playbackSpeed.value))
        publish(player)
    }

    fun playQueue(queueId: Long) {
        viewModelScope.launch {
            val songs = repository.queueSongs(queueId)
            playSongs(songs, 0, queueId)
        }
    }

    fun enqueueNext(song: SongEntity) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        player.addMediaItem(index + 1, mediaItem(song))
    }

    fun addToQueue(song: SongEntity) {
        controller?.addMediaItem(mediaItem(song))
    }

    fun saveQueue(name: String, songs: List<SongEntity>) {
        viewModelScope.launch { repository.createQueue(name, songs.map { it.mediaId }) }
    }

    fun saveCurrentQueue(name: String) {
        val player = controller ?: return
        val ids = (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index).mediaId.toLongOrNull()
        }
        viewModelScope.launch { repository.createQueue(name, ids) }
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

    fun playlistSongs(id: Long) = repository.observePlaylistSongs(id)
    fun queueSongs(id: Long) = repository.observeQueueSongs(id)

    fun togglePlayPause() {
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
        controller?.seekToNextMediaItem()
    }

    /** The original restarts the current track after ten seconds. */
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > 10_000L) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
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
        _eq.value = _eq.value.copy(enabled = enabled)
        pushEq()
    }

    fun setEqGain(index: Int, gain: Float) {
        val gains = _eq.value.gains.toMutableList()
        if (index !in gains.indices) return
        gains[index] = gain.coerceIn(-15f, 15f)
        _eq.value = _eq.value.copy(gains = gains)
        pushEq()
    }

    fun setEqPreset(name: String) {
        val gains = EQ_PRESETS[name] ?: return
        _eq.value = _eq.value.copy(gains = gains)
        pushEq()
    }

    private fun pushEq() {
        val player = controller ?: return
        val state = _eq.value
        val args = Bundle().apply {
            putBoolean("enabled", state.enabled)
            putFloatArray("gains", state.gains.toFloatArray())
        }
        player.sendCustomCommand(SessionCommand(PlaybackService.CMD_EQ_SET, Bundle.EMPTY), args)
    }

    private fun refreshEq(player: MediaController) {
        val future = player.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_EQ_GET, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching {
                    val extras = future.get().extras
                    val gains = extras.getFloatArray("gains")?.toList()
                    _eq.value = EqUiState(
                        enabled = extras.getBoolean("enabled", true),
                        gains = gains ?: _eq.value.gains,
                    )
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        controller?.volume = 1f
        if (minutes <= 0) {
            _sleepDeadline.value = null
            return
        }
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        _sleepDeadline.value = deadline
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            val player = controller ?: return@launch
            repeat(20) {
                player.volume = (1f - (it + 1) / 20f).coerceAtLeast(0f)
                delay(200)
            }
            player.pause()
            player.volume = 1f
            _sleepDeadline.value = null
        }
    }

    fun setLanguage(value: String) = settings.setLanguage(value)
    fun setTheme(value: String) = settings.setTheme(value)
    fun setAccent(value: String) = settings.setAccent(value)
    fun setIncludeOnly(value: Boolean) = settings.setIncludeOnly(value)
    fun setQueueConflict(value: String) = settings.setQueueConflict(value)

    fun toggleFavorite(mediaId: Long) {
        if (mediaId <= 0) return
        viewModelScope.launch { repository.toggleFavorite(mediaId) }
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
                .setArtworkUri(MusicRepository.albumArtUri(song.albumId))
                .build()
        )
        .build()

    override fun onCleared() {
        sleepJob?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let { if (it.isDone && !it.isCancelled) runCatching { it.get().release() } }
        controller = null
        controllerFuture = null
        super.onCleared()
    }

    companion object {
        val EQ_PRESETS: Map<String, List<Float>> = linkedMapOf(
            "Flat" to List(10) { 0f },
            "Rock" to listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
            "Pop" to listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, 0f, 1f),
            "Jazz" to listOf(4f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
            "Classical" to listOf(4f, 3f, 1f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
            "Bass" to listOf(8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Vocal" to listOf(-2f, -1f, 0f, 3f, 5f, 5f, 3f, 1f, 0f, -1f),
            "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        )
    }
}
