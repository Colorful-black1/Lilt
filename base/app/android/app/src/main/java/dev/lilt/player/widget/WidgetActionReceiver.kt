package dev.lilt.player.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.lilt.player.MainActivity
import dev.lilt.player.playback.PlaybackService

/**
 * Handles the three transport buttons with a short-lived MediaController. The
 * controller talks to the same session as the notification and the app, so the
 * widget never owns playback state itself.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_OPEN) {
            openApp(context)
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    runCatching {
                        when (action) {
                            ACTION_TOGGLE -> {
                                if (controller.mediaItemCount == 0) {
                                    openApp(context)
                                } else if (controller.isPlaying) {
                                    controller.pause()
                                } else {
                                    controller.play()
                                }
                            }
                            ACTION_NEXT -> controller.seekToNextMediaItem()
                            ACTION_PREVIOUS -> {
                                if (controller.currentPosition > 10_000L) {
                                    controller.seekTo(0L)
                                } else {
                                    controller.seekToPreviousMediaItem()
                                }
                            }
                        }
                    }
                    controller.release()
                }
                pending.finish()
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun openApp(context: Context) {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_TOGGLE = "dev.lilt.player.WIDGET_TOGGLE"
        const val ACTION_PREVIOUS = "dev.lilt.player.WIDGET_PREVIOUS"
        const val ACTION_NEXT = "dev.lilt.player.WIDGET_NEXT"
        const val ACTION_OPEN = "dev.lilt.player.WIDGET_OPEN"
    }
}
