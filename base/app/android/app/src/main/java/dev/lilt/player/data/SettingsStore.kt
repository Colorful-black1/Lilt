package dev.lilt.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.lilt.player.library.clampFloatingFontSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

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
    val lyricsFontSize: StateFlow<Float> =
        MutableStateFlow(prefs.getFloat("lyrics_font_size", 15f))
    val lyricsOpacity: StateFlow<Float> =
        MutableStateFlow(prefs.getFloat("lyrics_opacity", 1f))
    val floatingLyrics: StateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean("floating_lyrics", false))
    val floatingLyricsLocked: StateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean("floating_lyrics_locked", false))
    val floatingFontSize: StateFlow<Float> =
        MutableStateFlow(clampFloatingFontSize(prefs.getFloat("floating_font_size", 26f)))

    /**
     * The overlay service and the app share one preferences file. The service
     * can close or lock the overlay itself, so the StateFlows must follow
     * external writes as well as local setters.
     */
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "floating_lyrics" ->
                    (floatingLyrics as MutableStateFlow).value =
                        prefs.getBoolean(key, false)
                "floating_lyrics_locked" ->
                    (floatingLyricsLocked as MutableStateFlow).value =
                        prefs.getBoolean(key, false)
                "floating_font_size" ->
                    (floatingFontSize as MutableStateFlow).value =
                        clampFloatingFontSize(prefs.getFloat(key, 26f))
                "lyrics_opacity" ->
                    (lyricsOpacity as MutableStateFlow).value =
                        prefs.getFloat(key, 1f)
                "lyrics_font_size" ->
                    (lyricsFontSize as MutableStateFlow).value =
                        prefs.getFloat(key, 15f)
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

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

    /** Font size is kept on half-point steps between 12 and 24 sp. */
    fun setLyricsFontSize(value: Float) {
        val cleaned = ((value.coerceIn(12f, 24f) * 2f).roundToInt() / 2f)
        prefs.edit { putFloat("lyrics_font_size", cleaned) }
        (lyricsFontSize as MutableStateFlow).value = cleaned
    }

    fun setLyricsOpacity(value: Float) {
        val cleaned = value.coerceIn(0.35f, 1f)
        prefs.edit { putFloat("lyrics_opacity", cleaned) }
        (lyricsOpacity as MutableStateFlow).value = cleaned
    }

    fun setFloatingLyrics(value: Boolean) {
        prefs.edit { putBoolean("floating_lyrics", value) }
        (floatingLyrics as MutableStateFlow).value = value
    }

    fun setFloatingLyricsLocked(value: Boolean) {
        prefs.edit { putBoolean("floating_lyrics_locked", value) }
        (floatingLyricsLocked as MutableStateFlow).value = value
    }

    /** Floating sizes use half-point steps between 18 and 44 sp. */
    fun setFloatingFontSize(value: Float) {
        val cleaned = clampFloatingFontSize(value)
        prefs.edit { putFloat("floating_font_size", cleaned) }
        (floatingFontSize as MutableStateFlow).value = cleaned
    }

    fun snapshotForBackup(): BackupSettings = BackupSettings(
        language = language.value,
        theme = theme.value,
        accent = accent.value,
        includeOnly = includeOnly.value,
        queueConflict = queueConflict.value,
        playbackSpeed = playbackSpeed.value,
        songSort = songSort.value,
        songSortDescending = songSortDescending.value,
        fadeInMs = fadeInMs.value,
        fadeOutMs = fadeOutMs.value,
        sleepHours = sleepHours.value,
        sleepMinutes = sleepMinutes.value,
        sleepTracks = sleepTracks.value,
        sleepWaitTillEnd = sleepWaitTillEnd.value,
        sleepFade = sleepFade.value,
        rememberQueuePositions = rememberQueuePositions.value,
        lyricsFontSize = lyricsFontSize.value,
        lyricsOpacity = lyricsOpacity.value,
        floatingLyrics = floatingLyrics.value,
        floatingLyricsLocked = floatingLyricsLocked.value,
        floatingFontSize = floatingFontSize.value,
    )

    /** Applies typed, range-checked values from a backup through the normal setters. */
    fun restoreFromBackup(backup: BackupSettings) {
        setLanguage(backup.language)
        setTheme(backup.theme)
        setAccent(backup.accent)
        setIncludeOnly(backup.includeOnly)
        setQueueConflict(backup.queueConflict)
        setPlaybackSpeed(backup.playbackSpeed)
        setSongSort(backup.songSort)
        setSongSortDescending(backup.songSortDescending)
        setFadeInMs(backup.fadeInMs)
        setFadeOutMs(backup.fadeOutMs)
        setSleepDefaults(
            hours = backup.sleepHours,
            minutes = backup.sleepMinutes,
            tracks = backup.sleepTracks,
            waitTillEnd = backup.sleepWaitTillEnd,
            fade = backup.sleepFade,
        )
        setRememberQueuePositions(backup.rememberQueuePositions)
        setLyricsFontSize(backup.lyricsFontSize)
        setLyricsOpacity(backup.lyricsOpacity)
        setFloatingLyrics(backup.floatingLyrics)
        setFloatingLyricsLocked(backup.floatingLyricsLocked)
        setFloatingFontSize(backup.floatingFontSize)
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
