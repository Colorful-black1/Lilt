package dev.lilt.player.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** The one metadata shape used by both the app and the restored play queue. */
fun songMediaItem(song: SongEntity): MediaItem = MediaItem.Builder()
    .setMediaId(song.mediaId.toString())
    .setUri(song.contentUri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setDurationMs(song.durationMs)
            .setArtworkUri(song.artworkUri?.let(Uri::parse))
            .build()
    )
    .build()
