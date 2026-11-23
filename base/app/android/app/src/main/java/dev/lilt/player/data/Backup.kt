package dev.lilt.player.data

import dev.lilt.player.library.EQ_BANDS
import org.json.JSONArray
import org.json.JSONObject

/** The JSON envelope shared by every Lilt backup. */
data class LiltBackup(
    val format: Int = FORMAT,
    val platform: String = PLATFORM,
    val created: Long = 0L,
    val songs: List<BackupSongRef> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val queues: List<BackupQueue> = emptyList(),
    val activeQueueIndex: Int = -1,
    val songStates: List<BackupSongState> = emptyList(),
    val history: List<BackupHistory> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val lyrics: List<BackupLyrics> = emptyList(),
    val tags: List<BackupTag> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
    val searches: List<BackupSearch> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    val eq: BackupEq? = null,
) {
    fun encode(): String {
        val root = JSONObject()
        root.put("app", APP)
        root.put("format", format)
        root.put("platform", platform)
        root.put("created", created)
        root.put(
            "songs",
            JSONArray().apply {
                songs.forEach { song ->
                    put(
                        JSONObject().apply {
                            put("mediaId", song.mediaId)
                            put("title", song.title)
                            put("artist", song.artist)
                            put("album", song.album)
                            put("folder", song.folder)
                            put("displayName", song.displayName)
                            put("durationMs", song.durationMs)
                        },
                    )
                }
            },
        )
        root.put(
            "playlists",
            JSONArray().apply {
                playlists.forEach { playlist ->
                    put(
                        JSONObject().apply {
                            put("name", playlist.name)
                            put("createdAt", playlist.createdAt)
                            put("songs", JSONArray(playlist.songs))
                        },
                    )
                }
            },
        )
        root.put(
            "queues",
            JSONArray().apply {
                queues.forEach { queue ->
                    put(
                        JSONObject().apply {
                            put("name", queue.name)
                            put("createdAt", queue.createdAt)
                            put("lastIndex", queue.lastIndex)
                            put("lastPositionMs", queue.lastPositionMs)
                            put("songs", JSONArray(queue.songs))
                        },
                    )
                }
            },
        )
        root.put("activeQueueIndex", activeQueueIndex)
        root.put("songStates", songStates.toJsonArray { state ->
            JSONObject().apply {
                put("mediaId", state.mediaId)
                put("favorite", state.favorite)
                put("playCount", state.playCount)
                put("lastPlayedAt", state.lastPlayedAt)
            }
        })
        root.put("history", history.toJsonArray { entry ->
            JSONObject().apply {
                put("mediaId", entry.mediaId)
                put("playedAt", entry.playedAt)
            }
        })
        root.put("bookmarks", bookmarks.toJsonArray { bookmark ->
            JSONObject().apply {
                put("mediaId", bookmark.mediaId)
                put("positionMs", bookmark.positionMs)
                put("note", bookmark.note)
                put("createdAt", bookmark.createdAt)
            }
        })
        root.put("lyrics", lyrics.toJsonArray { lyric ->
            JSONObject().apply {
                put("mediaId", lyric.mediaId)
                put("text", lyric.text)
                put("offsetMs", lyric.offsetMs)
                put("updatedAt", lyric.updatedAt)
            }
        })
        root.put("tags", tags.toJsonArray { tag ->
            JSONObject().apply {
                put("mediaId", tag.mediaId)
                put("title", tag.title)
                put("artist", tag.artist)
                put("album", tag.album)
                put("year", tag.year)
                put("track", tag.track)
                put("updatedAt", tag.updatedAt)
            }
        })
        root.put("rules", rules.toJsonArray { rule ->
            JSONObject().apply {
                put("path", rule.path)
                put("kind", rule.kind)
            }
        })
        root.put("searches", searches.toJsonArray { search ->
            JSONObject().apply {
                put("query", search.query)
                put("searchedAt", search.searchedAt)
            }
        })
        root.put("settings", settings.toJson())
        eq?.let { root.put("eq", it.toJson()) }
        return root.toString(2)
    }

    fun preview(): BackupPreview = BackupPreview(
        created = created,
        songs = songs.size,
        playlists = playlists.size,
        queues = queues.size,
        favorites = songStates.count { it.favorite },
        lyrics = lyrics.size,
        bookmarks = bookmarks.size,
        settings = true,
    )

    companion object {
        const val APP = "lilt"
        const val FORMAT = 1
        const val PLATFORM = "android"

        fun decode(text: String): LiltBackup {
            val root = JSONObject(text)
            require(root.optString("app") == APP) {
                "This file was not created by Lilt."
            }
            val format = root.optInt("format", 0)
            require(format == FORMAT) {
                "Backup format $format is not supported by this build (expected $FORMAT)."
            }
            require(root.optString("platform") == PLATFORM) {
                "This backup was created on a computer. Restore it in Lilt for desktop."
            }
            val songs = root.optJSONArray("songs").objects().mapNotNull { item ->
                val id = item.longOr("mediaId", 0L)
                if (id <= 0L) null else BackupSongRef(
                    mediaId = id,
                    title = item.stringOr("title", ""),
                    artist = item.stringOr("artist", ""),
                    album = item.stringOr("album", ""),
                    folder = item.stringOr("folder", ""),
                    displayName = item.stringOr("displayName", ""),
                    durationMs = item.longOr("durationMs", 0L).coerceAtLeast(0L),
                )
            }
            val playlists = root.optJSONArray("playlists").objects().mapNotNull { item ->
                val name = item.stringOr("name", "").trim()
                if (name.isEmpty()) null else BackupPlaylist(
                    name = name,
                    createdAt = item.longOr("createdAt", 0L),
                    songs = item.optJSONArray("songs").longs(),
                )
            }
            val queues = root.optJSONArray("queues").objects().mapNotNull { item ->
                val name = item.stringOr("name", "").trim()
                if (name.isEmpty()) null else BackupQueue(
                    name = name,
                    createdAt = item.longOr("createdAt", 0L),
                    lastIndex = item.intOr("lastIndex", 0, 0, 1_000_000),
                    lastPositionMs = item.longOr("lastPositionMs", 0L).coerceAtLeast(0L),
                    songs = item.optJSONArray("songs").longs(),
                )
            }
            val settings = root.optJSONObject("settings")?.let(BackupSettings::fromJson)
                ?: BackupSettings()
            return LiltBackup(
                format = format,
                platform = PLATFORM,
                created = root.longOr("created", 0L),
                songs = songs,
                playlists = playlists,
                queues = queues,
                activeQueueIndex = root.intOr("activeQueueIndex", -1, -1, 1_000_000),
                songStates = root.optJSONArray("songStates").objects().mapNotNull { item ->
                    val id = item.longOr("mediaId", 0L)
                    if (id <= 0L) null else BackupSongState(
                        mediaId = id,
                        favorite = item.booleanOr("favorite", false),
                        playCount = item.intOr("playCount", 0, 0, 10_000_000),
                        lastPlayedAt = item.longOr("lastPlayedAt", 0L).coerceAtLeast(0L),
                    )
                },
                history = root.optJSONArray("history").objects().mapNotNull { item ->
                    val id = item.longOr("mediaId", 0L)
                    if (id <= 0L) null else BackupHistory(
                        mediaId = id,
                        playedAt = item.longOr("playedAt", 0L).coerceAtLeast(0L),
                    )
                },
                bookmarks = root.optJSONArray("bookmarks").objects().mapNotNull { item ->
                    val id = item.longOr("mediaId", 0L)
                    if (id <= 0L) null else BackupBookmark(
                        mediaId = id,
                        positionMs = item.longOr("positionMs", 0L).coerceAtLeast(0L),
                        note = item.stringOr("note", ""),
                        createdAt = item.longOr("createdAt", 0L).coerceAtLeast(0L),
                    )
                },
                lyrics = root.optJSONArray("lyrics").objects().mapNotNull { item ->
                    val id = item.longOr("mediaId", 0L)
                    val text = item.stringOr("text", "")
                    if (id <= 0L || text.isBlank()) null else BackupLyrics(
                        mediaId = id,
                        text = text,
                        offsetMs = item.intOr("offsetMs", 0, -60_000, 60_000),
                        updatedAt = item.longOr("updatedAt", 0L),
                    )
                },
                tags = root.optJSONArray("tags").objects().mapNotNull { item ->
                    val id = item.longOr("mediaId", 0L)
                    if (id <= 0L) null else BackupTag(
                        mediaId = id,
                        title = item.stringOr("title", ""),
                        artist = item.stringOr("artist", ""),
                        album = item.stringOr("album", ""),
                        year = item.intOr("year", 0, 0, 9_999),
                        track = item.intOr("track", 0, 0, 99_999),
                        updatedAt = item.longOr("updatedAt", 0L),
                    )
                },
                rules = root.optJSONArray("rules").objects().mapNotNull { item ->
                    val path = item.stringOr("path", "").trim()
                    val kind = item.stringOr("kind", "").trim()
                    if (path.isEmpty() || (kind != "include" && kind != "exclude")) {
                        null
                    } else {
                        BackupRule(path, kind)
                    }
                },
                searches = root.optJSONArray("searches").objects().mapNotNull { item ->
                    val query = item.stringOr("query", "").trim()
                    if (query.isEmpty()) null else BackupSearch(
                        query = query,
                        searchedAt = item.longOr("searchedAt", 0L),
                    )
                },
                settings = settings,
                eq = root.optJSONObject("eq")?.let(BackupEq::fromJson),
            )
        }
    }
}

data class BackupSongRef(
    val mediaId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val displayName: String,
    val durationMs: Long,
)

data class BackupPlaylist(
    val name: String,
    val createdAt: Long,
    val songs: List<Long>,
)

data class BackupQueue(
    val name: String,
    val createdAt: Long,
    val lastIndex: Int,
    val lastPositionMs: Long,
    val songs: List<Long>,
)

data class BackupSongState(
    val mediaId: Long,
    val favorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long,
)

data class BackupHistory(val mediaId: Long, val playedAt: Long)

data class BackupBookmark(
    val mediaId: Long,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

data class BackupLyrics(
    val mediaId: Long,
    val text: String,
    val offsetMs: Int,
    val updatedAt: Long,
)

data class BackupTag(
    val mediaId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val track: Int,
    val updatedAt: Long,
)

data class BackupRule(val path: String, val kind: String)

data class BackupSearch(val query: String, val searchedAt: Long)

data class BackupSettings(
    val language: String = "en",
    val theme: String = "black",
    val accent: String = "ember",
    val includeOnly: Boolean = false,
    val queueConflict: String = "replace",
    val playbackSpeed: Float = 1f,
    val songSort: String = "title",
    val songSortDescending: Boolean = false,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val sleepHours: Int = 1,
    val sleepMinutes: Int = 30,
    val sleepTracks: Int = 10,
    val sleepWaitTillEnd: Boolean = false,
    val sleepFade: Boolean = true,
    val rememberQueuePositions: Boolean = true,
    val lyricsFontSize: Float = 15f,
    val lyricsOpacity: Float = 1f,
    val floatingLyrics: Boolean = false,
    val floatingLyricsLocked: Boolean = false,
    val floatingFontSize: Float = 26f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("language", language)
        put("theme", theme)
        put("accent", accent)
        put("includeOnly", includeOnly)
        put("queueConflict", queueConflict)
        put("playbackSpeed", playbackSpeed)
        put("songSort", songSort)
        put("songSortDescending", songSortDescending)
        put("fadeInMs", fadeInMs)
        put("fadeOutMs", fadeOutMs)
        put("sleepHours", sleepHours)
        put("sleepMinutes", sleepMinutes)
        put("sleepTracks", sleepTracks)
        put("sleepWaitTillEnd", sleepWaitTillEnd)
        put("sleepFade", sleepFade)
        put("rememberQueuePositions", rememberQueuePositions)
        put("lyricsFontSize", lyricsFontSize)
        put("lyricsOpacity", lyricsOpacity)
        put("floatingLyrics", floatingLyrics)
        put("floatingLyricsLocked", floatingLyricsLocked)
        put("floatingFontSize", floatingFontSize)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupSettings = BackupSettings(
            language = json.stringOr("language", "en").takeIf { it == "zh" } ?: "en",
            theme = json.stringOr("theme", "black")
                .takeIf { it in setOf("black", "dark", "light", "system") } ?: "black",
            accent = json.stringOr("accent", "ember")
                .takeIf { it in setOf("ember", "sage", "sky", "rose", "violet") } ?: "ember",
            includeOnly = json.booleanOr("includeOnly", false),
            queueConflict = json.stringOr("queueConflict", "replace")
                .takeIf { it == "merge" } ?: "replace",
            playbackSpeed = json.floatOr("playbackSpeed", 1f, 0.5f, 3f),
            songSort = json.stringOr("songSort", "title").let { value ->
                if (value in setOf(
                        "title",
                        "artist",
                        "album",
                        "duration",
                        "date_added",
                        "play_count",
                        "last_played",
                        "track",
                        "file_name",
                    )
                ) {
                    value
                } else {
                    "title"
                }
            },
            songSortDescending = json.booleanOr("songSortDescending", false),
            fadeInMs = json.intOr("fadeInMs", 0, 0, 4_000),
            fadeOutMs = json.intOr("fadeOutMs", 0, 0, 4_000),
            sleepHours = json.intOr("sleepHours", 1, 0, 23),
            sleepMinutes = json.intOr("sleepMinutes", 30, 0, 59),
            sleepTracks = json.intOr("sleepTracks", 10, 1, 99),
            sleepWaitTillEnd = json.booleanOr("sleepWaitTillEnd", false),
            sleepFade = json.booleanOr("sleepFade", true),
            rememberQueuePositions = json.booleanOr("rememberQueuePositions", true),
            lyricsFontSize = json.floatOr("lyricsFontSize", 15f, 12f, 24f),
            lyricsOpacity = json.floatOr("lyricsOpacity", 1f, 0.35f, 1f),
            floatingLyrics = json.booleanOr("floatingLyrics", false),
            floatingLyricsLocked = json.booleanOr("floatingLyricsLocked", false),
            floatingFontSize = json.floatOr("floatingFontSize", 26f, 18f, 44f),
        )
    }
}

data class BackupEq(
    val enabled: Boolean = true,
    val gains: List<Float> = List(EQ_BANDS) { 0f },
    val preampMode: String = "off",
    val headroomDb: Float = 6f,
    val boostDb: Float = 2f,
    val bass: Float = 0f,
    val virtualizer: Float = 0f,
    val balance: Float = 100f,
    val preset: String = "Flat",
    val perDevice: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("gains", JSONArray(gains))
        put("preampMode", preampMode)
        put("headroomDb", headroomDb)
        put("boostDb", boostDb)
        put("bass", bass)
        put("virtualizer", virtualizer)
        put("balance", balance)
        put("preset", preset)
        put("perDevice", perDevice)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupEq {
            val gains = json.optJSONArray("gains").floats()
            return BackupEq(
                enabled = json.booleanOr("enabled", true),
                gains = if (gains.size == EQ_BANDS) gains else List(EQ_BANDS) { 0f },
                preampMode = json.stringOr("preampMode", "off").ifBlank { "off" },
                headroomDb = json.floatOr("headroomDb", 6f, 0f, 24f),
                boostDb = json.floatOr("boostDb", 2f, 0f, 12f),
                bass = json.floatOr("bass", 0f, 0f, 1f),
                virtualizer = json.floatOr("virtualizer", 0f, 0f, 1f),
                balance = json.floatOr("balance", 100f, 0f, 200f),
                preset = json.stringOr("preset", "Flat").ifBlank { "Flat" },
                perDevice = json.booleanOr("perDevice", true),
            )
        }
    }
}

data class BackupPreview(
    val created: Long,
    val songs: Int,
    val playlists: Int,
    val queues: Int,
    val favorites: Int,
    val lyrics: Int,
    val bookmarks: Int,
    val settings: Boolean,
)

data class RestoreSummary(
    val playlists: Int,
    val queues: Int,
    val songs: Int,
    val favorites: Int,
    val lyrics: Int,
    val bookmarks: Int,
    val unresolved: Int,
)

/**
 * Resolves backup song references against the locally scanned library. The
 * MediaStore id is preferred; a rescan that changed ids can still match by
 * folder + file name, then by title/artist/duration.
 */
fun resolveBackupSongIds(
    backup: LiltBackup,
    current: List<SongEntity>,
): Map<Long, Long> {
    val byId = current.associateBy { it.mediaId }
    val byFile = current.groupBy { fileKey(it.folder, it.displayName) }
    val byMeta = current.groupBy {
        "${normalize(it.title)}\u0000${normalize(it.artist)}\u0000${it.durationMs / 1000}"
    }
    val resolved = LinkedHashMap<Long, Long>()
    backup.songs.forEach { song ->
        val match = byId[song.mediaId]
            ?: byFile[fileKey(song.folder, song.displayName)]?.singleOrNull()
            ?: byMeta[
                "${normalize(song.title)}\u0000${normalize(song.artist)}\u0000${song.durationMs / 1000}"
            ]?.singleOrNull()
        if (match != null) resolved[song.mediaId] = match.mediaId
    }
    return resolved
}

fun unresolvedCount(backup: LiltBackup, resolved: Map<Long, Long>): Int =
    backup.songs.count { it.mediaId !in resolved }

private fun fileKey(folder: String, name: String): String =
    "${normalize(folder.trimEnd('/'))}/${normalize(name)}"

private fun normalize(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

private fun JSONObject.stringOr(key: String, fallback: String): String =
    optString(key, fallback).ifBlank { fallback }

private fun JSONObject.booleanOr(key: String, fallback: Boolean): Boolean =
    if (has(key) && !isNull(key)) optBoolean(key, fallback) else fallback

private fun JSONObject.intOr(key: String, fallback: Int, min: Int, max: Int): Int {
    val value = if (has(key) && !isNull(key)) optInt(key, fallback) else fallback
    return value.coerceIn(min, max)
}

private fun JSONObject.longOr(key: String, fallback: Long): Long {
    val value = if (has(key) && !isNull(key)) optLong(key, fallback) else fallback
    return value
}

private fun JSONObject.floatOr(key: String, fallback: Float, min: Float, max: Float): Float {
    val value = if (has(key) && !isNull(key)) optDouble(key, fallback.toDouble()) else fallback.toDouble()
    return if (value.isFinite()) value.toFloat().coerceIn(min, max) else fallback
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)
    }
}

private fun JSONArray?.longs(): List<Long> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optLong(index, 0L).takeIf { it > 0L }
    }
}

private fun JSONArray?.floats(): List<Float> {
    if (this == null) return emptyList()
    return (0 until length()).map { index ->
        optDouble(index, 0.0).toFloat()
    }
}

private fun <T> List<T>.toJsonArray(build: (T) -> JSONObject): JSONArray =
    JSONArray().apply { forEach { put(build(it)) } }
