package dev.lilt.player

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.lilt.player.data.LiltDatabase
import dev.lilt.player.data.LyricsEntity
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.SettingsStore
import dev.lilt.player.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LockScreenState(
    val mediaId: Long = -1L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1L,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val itemCount: Int = 0,
) {
    val hasTrack: Boolean get() = mediaId > 0L
}

/**
 * A small controller for the lock-screen activity. It deliberately does not
 * reuse LiltViewModel: the activity is short-lived and must not record history
 * or count plays a second time.
 */
class LockScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settings = SettingsStore(application)
    private val repository = MusicRepository(application, LiltDatabase.get(application), settings)

    val language = settings.language
    val accent = settings.accent
    val favoriteIds: StateFlow<Set<Long>> = repository.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _state = MutableStateFlow(LockScreenState())
    val state: StateFlow<LockScreenState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsEntity?>(null)
    val lyrics: StateFlow<LyricsEntity?> = _lyrics.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var lyricsJob: Job? = null
    private var lyricsMediaId = -1L
    private var released = false
    private val clearAbLoop = SessionCommand(PlaybackService.CMD_AB_CLEAR, Bundle.EMPTY)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (released) return@addListener
                runCatching {
                    val c = future.get()
                    controller = c
                    c.addListener(listener)
                    publish(c)
                }
            },
            ContextCompat.getMainExecutor(app),
        )
        viewModelScope.launch {
            while (isActive) {
                controller?.let { if (it.isPlaying) publish(it) }
                delay(250)
            }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId?.toLongOrNull() ?: -1L
        loadLyrics(mediaId)
        _state.value = LockScreenState(
            mediaId = mediaId,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            album = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
            albumId = item?.mediaMetadata?.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            itemCount = player.mediaItemCount,
        )
    }

    /**
     * The lock screen only reads saved lyrics; it never starts an online
     * lookup or records playback history. Room emits again after the user
     * edits or shifts the lyrics and unlocks.
     */
    private fun loadLyrics(mediaId: Long) {
        if (mediaId == lyricsMediaId) return
        lyricsMediaId = mediaId
        lyricsJob?.cancel()
        _lyrics.value = null
        if (mediaId <= 0L) return
        lyricsJob = viewModelScope.launch {
            repository.observeLyrics(mediaId).collect { _lyrics.value = it }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 10_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        if (c.isSessionCommandAvailable(clearAbLoop)) {
            c.sendCustomCommand(clearAbLoop, Bundle.EMPTY)
        }
        c.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        publish(c)
    }

    fun toggleFavorite() {
        val mediaId = _state.value.mediaId
        if (mediaId <= 0L) return
        viewModelScope.launch { repository.toggleFavorite(mediaId) }
    }

    override fun onCleared() {
        released = true
        lyricsJob?.cancel()
        lyricsJob = null
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        controllerFuture = null
        super.onCleared()
    }
}
