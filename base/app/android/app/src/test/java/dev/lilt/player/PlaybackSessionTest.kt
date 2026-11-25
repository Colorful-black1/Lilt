package dev.lilt.player

import dev.lilt.player.data.PlaybackSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionTest {
    @Test
    fun sessionJsonRoundTripKeepsQueueAndPosition() {
        val original = PlaybackSession(
            queueIds = listOf(100L, 200L, 300L),
            index = 1,
            positionMs = 42_000L,
            activeQueueId = 7L,
            updatedAt = 123L,
        )
        val decoded = PlaybackSession.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun missingOrInvalidSessionIsRejected() {
        assertNull(PlaybackSession.fromJson(JSONObject("{}")))
        assertNull(
            PlaybackSession.fromJson(
                PlaybackSession(queueIds = emptyList(), index = 0, positionMs = 0L).toJson()
            )
        )
        assertNull(
            PlaybackSession.fromJson(
                PlaybackSession(queueIds = listOf(1L, 2L), index = 0).toJson().apply {
                    put("version", 99)
                }
            )
        )
    }

    @Test
    fun sessionClampsStaleIndexAndNegativePosition() {
        val json = PlaybackSession(
            queueIds = listOf(10L, 20L),
            index = 9,
            positionMs = -50L,
        ).toJson()
        val decoded = PlaybackSession.fromJson(json)!!
        assertEquals(1, decoded.index)
        assertEquals(0L, decoded.positionMs)
    }
}
