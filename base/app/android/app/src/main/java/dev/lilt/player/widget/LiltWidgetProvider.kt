package dev.lilt.player.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Thin provider: all rendering lives in [WidgetRenderer]. The playback service
 * pushes updates, so the widget has no polling period of its own.
 */
class LiltWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetRenderer.restore(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRenderer.restore(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        WidgetRenderer.restore(context)
    }
}
