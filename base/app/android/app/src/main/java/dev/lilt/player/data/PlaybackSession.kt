package dev.lilt.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last playback session, saved so a cold app start can show the previous
 * song in the mini player and continue from the same position. It intentionally
 * excludes sleep timers, A-B loops and other one-shot controls.
 */
data class PlaybackSession(
    val queueIds: List<Long> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val activeQueueId: Long = -1L,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("queue", JSONArray(queueIds))
        put("index", index)
        put("positionMs", positionMs)
        put("activeQueueId", activeQueueId)
        put("updatedAt", updatedAt)
    }

    companion object {
        const val VERSION = 1

        fun fromJson(json: JSONObject): PlaybackSession? {
            if (json.optInt("version", 0) != VERSION) return null
            val array = json.optJSONArray("queue") ?: return null
            val ids = ArrayList<Long>(array.length())
            for (position in 0 until array.length()) {
                val id = array.optLong(position, -1L)
                if (id > 0L) ids += id
            }
            if (ids.isEmpty()) return null
            return PlaybackSession(
                queueIds = ids,
                index = json.optInt("index", 0).coerceIn(0, ids.lastIndex),
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                activeQueueId = json.optLong("activeQueueId", -1L),
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
    }
}

/** Atomic, local-only storage for [PlaybackSession]. Not part of backups. */
class PlaybackSessionStore(context: Context) {
    private val file = File(context.filesDir, "playback_session.json")

    @Synchronized
    fun load(): PlaybackSession? {
        if (!file.isFile) return null
        return runCatching {
            PlaybackSession.fromJson(JSONObject(file.readText()))
        }.getOrNull()
    }

    @Synchronized
    fun save(session: PlaybackSession) {
        if (session.queueIds.isEmpty()) {
            clear()
            return
        }
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(session.toJson().toString())
            if (file.exists() && !file.delete()) return
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
