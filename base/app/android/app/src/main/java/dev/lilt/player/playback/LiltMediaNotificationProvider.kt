package dev.lilt.player.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import dev.lilt.player.R

/**
 * Two extra notification controls for Android 12 and older.
 *
 * Android 13+ derives the media card buttons from the session's media button
 * preferences instead of the notification actions. The same two command buttons
 * are registered there by [PlaybackService]; this provider keeps the older
 * notification layout in the KuGou order: like, previous, play/pause, next,
 * lyrics. The transport group stays centred because the two extra actions sit
 * on the outside.
 */
@OptIn(UnstableApi::class)
class LiltMediaNotificationProvider(
    context: Context,
    private val isFavorite: () -> Boolean,
    private val isChinese: () -> Boolean,
) : DefaultMediaNotificationProvider(context) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        // The default buttons are enough here. The custom command buttons are
        // added explicitly on both sides so the play button remains the centre.
        val transport = super.getMediaButtons(
            session,
            playerCommands,
            ImmutableList.of(),
            showPauseButton,
        )
        val chinese = isChinese()
        return ImmutableList.builder<CommandButton>()
            .add(favoriteButton(isFavorite(), chinese))
            .addAll(transport)
            .add(floatingLyricsButton(chinese))
            .build()
    }
}

internal fun favoriteButton(favorite: Boolean, chinese: Boolean): CommandButton =
    CommandButton.Builder(
        if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
    )
        .setSessionCommand(
            SessionCommand(PlaybackService.CMD_TOGGLE_FAVORITE, Bundle.EMPTY),
        )
        .setDisplayName(
            when {
                favorite && chinese -> "取消收藏"
                favorite -> "Remove favorite"
                chinese -> "收藏"
                else -> "Favorite"
            },
        )
        .build()

internal fun floatingLyricsButton(chinese: Boolean): CommandButton =
    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setCustomIconResId(R.drawable.ic_notification_lyrics)
        .setSessionCommand(
            SessionCommand(PlaybackService.CMD_TOGGLE_FLOATING_LYRICS, Bundle.EMPTY),
        )
        .setDisplayName(if (chinese) "悬浮歌词" else "Floating lyrics")
        .build()
