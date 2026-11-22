package dev.lilt.player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small, local preferences. No account and no network. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)

    val language: StateFlow<String> = MutableStateFlow(prefs.getString("language", "en") ?: "en")
    val theme: StateFlow<String> = MutableStateFlow(prefs.getString("theme", "black") ?: "black")
    val accent: StateFlow<String> = MutableStateFlow(prefs.getString("accent", "ember") ?: "ember")
    val includeOnly: StateFlow<Boolean> = MutableStateFlow(prefs.getBoolean("include_only", false))
    val queueConflict: StateFlow<String> =
        MutableStateFlow(prefs.getString("queue_conflict", "replace") ?: "replace")
    val playbackSpeed: StateFlow<Float> = MutableStateFlow(prefs.getFloat("speed", 1f))
    val activeQueueId: StateFlow<Long> = MutableStateFlow(prefs.getLong("active_queue", -1L))

    fun setLanguage(value: String) {
        prefs.edit { putString("language", value) }
        (language as MutableStateFlow).value = value
    }

    fun setTheme(value: String) {
        prefs.edit { putString("theme", value) }
        (theme as MutableStateFlow).value = value
    }

    fun setAccent(value: String) {
        prefs.edit { putString("accent", value) }
        (accent as MutableStateFlow).value = value
    }

    fun setIncludeOnly(value: Boolean) {
        prefs.edit { putBoolean("include_only", value) }
        (includeOnly as MutableStateFlow).value = value
    }

    fun setQueueConflict(value: String) {
        prefs.edit { putString("queue_conflict", value) }
        (queueConflict as MutableStateFlow).value = value
    }

    fun setPlaybackSpeed(value: Float) {
        prefs.edit { putFloat("speed", value) }
        (playbackSpeed as MutableStateFlow).value = value
    }

    fun setActiveQueue(id: Long) {
        prefs.edit { putLong("active_queue", id) }
        (activeQueueId as MutableStateFlow).value = id
    }
}
