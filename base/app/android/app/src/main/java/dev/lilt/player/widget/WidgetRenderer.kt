package dev.lilt.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.RemoteViews
import androidx.media3.common.Player
import dev.lilt.player.MainActivity
import dev.lilt.player.R
import dev.lilt.player.library.accentArgb
import dev.lilt.player.library.widgetProgress
import dev.lilt.player.library.widgetTimeLabel
import java.util.concurrent.Executors

/** Metadata pushed from the running playback service into the home-screen widget. */
data class WidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val artKey: String? = null,
    val accent: Int = 0xFFF2B46B.toInt(),
)

/**
 * Renders the RemoteViews card. The service calls [update] on player state
 * changes and once a second while playing; a single background thread decodes
 * album art so the main thread never blocks on the media provider.
 */
object WidgetRenderer {
    private val main = Handler(Looper.getMainLooper())
    private val artExecutor = Executors.newSingleThreadExecutor()
    private var loadedArtKey: String? = null
    private var loadedArt: Bitmap? = null
    private var pendingArtKey: String? = null
    private var failedArtKey: String? = null
    private var lastSnapshot: WidgetSnapshot? = null

    fun update(context: Context, player: Player?) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            ComponentName(app, LiltWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        val item = player?.currentMediaItem
        val snapshot = WidgetSnapshot(
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = player?.isPlaying == true,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = player?.duration?.takeIf { it > 0L } ?: 0L,
            artKey = item?.mediaMetadata?.artworkUri?.toString(),
            accent = accentArgb(
                app.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
                    .getString("accent", "ember") ?: "ember",
            ),
        )
        lastSnapshot = snapshot
        publish(app, ids, snapshot)
        loadArtwork(app, snapshot, ids)
    }

    /**
     * Re-renders the snapshot the playback service last pushed. The widget
     * provider runs in the same process, so a widget added while playback is
     * paused shows the paused track instead of an idle card. A fresh process
     * has no cached snapshot and falls back to idle.
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        val snapshot = lastSnapshot
        if (snapshot == null || snapshot.title.isBlank()) {
            idle(app)
            return
        }
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            ComponentName(app, LiltWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        publish(app, ids, snapshot)
        loadArtwork(app, snapshot, ids)
    }

    /** Shows the idle card when the playback service is gone. */
    fun idle(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            ComponentName(app, LiltWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        val snapshot = WidgetSnapshot(
            accent = accentArgb(
                app.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)
                    .getString("accent", "ember") ?: "ember",
            ),
        )
        lastSnapshot = snapshot
        publish(app, ids, snapshot)
    }

    private fun publish(context: Context, ids: IntArray, snapshot: WidgetSnapshot) {
        val art = loadedArt.takeIf { loadedArtKey != null && loadedArtKey == snapshot.artKey }
        val views = buildViews(context, snapshot, art)
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun buildViews(context: Context, snapshot: WidgetSnapshot, art: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lilt)
        val title = snapshot.title.ifBlank { context.getString(R.string.widget_idle_title) }
        val artist = if (snapshot.title.isBlank()) {
            context.getString(R.string.widget_idle_subtitle)
        } else {
            snapshot.artist.ifBlank { context.getString(R.string.widget_idle_title) }
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setTextViewText(
            R.id.widget_time,
            widgetTimeLabel(snapshot.positionMs, snapshot.durationMs),
        )
        views.setProgressBar(
            R.id.widget_progress,
            1000,
            widgetProgress(snapshot.positionMs, snapshot.durationMs),
            false,
        )
        views.setImageViewResource(
            R.id.widget_toggle,
            if (snapshot.isPlaying) R.drawable.ic_float_pause else R.drawable.ic_float_play,
        )
        if (art != null) {
            views.setImageViewBitmap(R.id.widget_cover, art)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        }
        views.setInt(R.id.widget_toggle, "setColorFilter", snapshot.accent)
        views.setInt(R.id.widget_previous, "setColorFilter", 0xFFF5F0E8.toInt())
        views.setInt(R.id.widget_next, "setColorFilter", 0xFFF5F0E8.toInt())

        views.setOnClickPendingIntent(
            R.id.widget_root,
            openIntent(context),
        )
        views.setOnClickPendingIntent(
            R.id.widget_previous,
            actionIntent(context, WidgetActionReceiver.ACTION_PREVIOUS, 101),
        )
        views.setOnClickPendingIntent(
            R.id.widget_toggle,
            actionIntent(context, WidgetActionReceiver.ACTION_TOGGLE, 102),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            actionIntent(context, WidgetActionReceiver.ACTION_NEXT, 103),
        )
        return views
    }

    private fun loadArtwork(context: Context, snapshot: WidgetSnapshot, ids: IntArray) {
        val key = snapshot.artKey
        if (
            key.isNullOrBlank() ||
            key == loadedArtKey ||
            key == pendingArtKey ||
            key == failedArtKey
        ) {
            return
        }
        pendingArtKey = key
        artExecutor.execute {
            val bitmap = runCatching { decodeArt(context, key) }.getOrNull()
            main.post {
                pendingArtKey = null
                if (bitmap != null) {
                    loadedArt = bitmap
                    loadedArtKey = key
                } else {
                    failedArtKey = key
                }
                val current = lastSnapshot
                if (current != null && current.artKey == key) {
                    publish(context, ids, current)
                }
            }
        }
    }

    private fun decodeArt(context: Context, key: String): Bitmap? {
        val uri = android.net.Uri.parse(key)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return null
                val side = minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
                val square = if (bitmap.width == side && bitmap.height == side) {
                    bitmap
                } else {
                    Bitmap.createBitmap(
                        bitmap,
                        (bitmap.width - side) / 2,
                        (bitmap.height - side) / 2,
                        side,
                        side,
                    )
                }
                if (square !== bitmap) bitmap.recycle()
                val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
                if (scaled !== square) square.recycle()
                scaled
            }
        }
    }

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            104,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
