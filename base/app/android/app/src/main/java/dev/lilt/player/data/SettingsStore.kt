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
    val songSort: StateFlow<String> =
        MutableStateFlow(prefs.getString("song_sort", "title") ?: "title")
    val songSortDescending: StateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean("song_sort_desc", false))
    val fadeInMs: StateFlow<Int> = MutableStateFlow(prefs.getInt("fade_in_ms", 0))
    val fadeOutMs: StateFlow<Int> = MutableStateFlow(prefs.getInt("fade_out_ms", 0))
    val sleepHours: StateFlow<Int> = MutableStateFlow(prefs.getInt("sleep_hours", 1))
    val sleepMinutes: StateFlow<Int> = MutableStateFlow(prefs.getInt("sleep_minutes", 30))
    val sleepTracks: StateFlow<Int> = MutableStateFlow(prefs.getInt("sleep_tracks", 10))
    val sleepWaitTillEnd: StateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean("sleep_wait_end", false))
    val sleepFade: StateFlow<Boolean> = MutableStateFlow(prefs.getBoolean("sleep_fade", true))
    val rememberQueuePositions: StateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean("remember_queue_positions", true))

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

    fun setSongSort(value: String) {
        prefs.edit { putString("song_sort", value) }
        (songSort as MutableStateFlow).value = value
    }

    fun setSongSortDescending(value: Boolean) {
        prefs.edit { putBoolean("song_sort_desc", value) }
        (songSortDescending as MutableStateFlow).value = value
    }

    /** Fade lengths are kept in 250 ms steps between off and four seconds. */
    fun setFadeInMs(value: Int) {
        val cleaned = ((value.coerceIn(0, 4_000) + 125) / 250) * 250
        prefs.edit { putInt("fade_in_ms", cleaned) }
        (fadeInMs as MutableStateFlow).value = cleaned
    }

    fun setFadeOutMs(value: Int) {
        val cleaned = ((value.coerceIn(0, 4_000) + 125) / 250) * 250
        prefs.edit { putInt("fade_out_ms", cleaned) }
        (fadeOutMs as MutableStateFlow).value = cleaned
    }

    fun setRememberQueuePositions(value: Boolean) {
        prefs.edit { putBoolean("remember_queue_positions", value) }
        (rememberQueuePositions as MutableStateFlow).value = value
    }

    fun setSleepDefaults(
        hours: Int,
        minutes: Int,
        tracks: Int,
        waitTillEnd: Boolean,
        fade: Boolean,
    ) {
        val cleanHours = hours.coerceIn(0, 23)
        val cleanMinutes = minutes.coerceIn(0, 59)
        val cleanTracks = tracks.coerceIn(1, 99)
        prefs.edit {
            putInt("sleep_hours", cleanHours)
            putInt("sleep_minutes", cleanMinutes)
            putInt("sleep_tracks", cleanTracks)
            putBoolean("sleep_wait_end", waitTillEnd)
            putBoolean("sleep_fade", fade)
        }
        (sleepHours as MutableStateFlow).value = cleanHours
        (sleepMinutes as MutableStateFlow).value = cleanMinutes
        (sleepTracks as MutableStateFlow).value = cleanTracks
        (sleepWaitTillEnd as MutableStateFlow).value = waitTillEnd
        (sleepFade as MutableStateFlow).value = fade
    }
}
