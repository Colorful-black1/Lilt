package dev.lilt.player

import dev.lilt.player.library.LrclibRecord
import dev.lilt.player.library.MatchOutcome
import dev.lilt.player.library.candidatesFromJson
import dev.lilt.player.library.candidatesToJson
import dev.lilt.player.library.chooseMatch
import dev.lilt.player.library.cleanAiText
import dev.lilt.player.library.inspectLyrics
import dev.lilt.player.library.normalizeBaseUrl
import dev.lilt.player.library.normalizeMatchText
import dev.lilt.player.library.rankCandidates
import dev.lilt.player.library.titleExact
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsTest {
    private fun record(
        title: String,
        artist: String,
        duration: Double,
        synced: String? = null,
        instrumental: Boolean = false,
    ) = LrclibRecord(
        id = 1,
        trackName = title,
        artistName = artist,
        albumName = "Album",
        duration = duration,
        instrumental = instrumental,
        syncedLyrics = synced,
    )

    @Test
    fun normalizesTitlesAndArtists() {
        assertEquals(
            "hotel california",
            normalizeMatchText("Hotel California - Remastered 2011"),
        )
        assertEquals("晴天", normalizeMatchText("晴天 (Live)"))
        assertTrue(titleExact("Hotel California", "Hotel California (Live)"))
        assertFalse(titleExact("Hello", "Hello Goodbye"))
    }

    @Test
    fun autoMatchesExactTitleArtistAndDuration() {
        val ranked = rankCandidates(
            records = listOf(
                record("晴天", "周杰伦", 270.0, "[00:29.30] 故事的小黃花"),
                record("晴天", "其他歌手", 270.0, "[00:29.30] x"),
            ),
            title = "晴天",
            artist = "周杰伦",
            durationSeconds = 270.0,
        )
        val outcome = chooseMatch(ranked)
        assertTrue(outcome is MatchOutcome.Best)
        assertEquals("周杰伦", (outcome as MatchOutcome.Best).candidate.record.artistName)
        assertTrue(outcome.candidate.score >= 0.90f)
    }

    @Test
    fun liveVersionsAreAmbiguousNotAutomatic() {
        val ranked = rankCandidates(
            records = listOf(
                record("晴天", "周杰伦", 299.0, "[00:29.36] 故事的小黃花"),
            ),
            title = "晴天",
            artist = "周杰伦",
            durationSeconds = 270.0,
        )
        assertTrue(chooseMatch(ranked) is MatchOutcome.Ambiguous)
    }

    @Test
    fun instrumentalResultsAreMarked() {
        val ranked = rankCandidates(
            records = listOf(record("晴天", "周杰伦", 270.0, instrumental = true)),
            title = "晴天",
            artist = "周杰伦",
            durationSeconds = 270.0,
        )
        assertEquals(MatchOutcome.Instrumental, chooseMatch(ranked))
    }

    @Test
    fun cleansAiFencesAndNotFound() {
        val raw = "```lrc\n[Verse 1]\n[00:01.00] Line one\n[00:02.00] Line two\n```"
        assertEquals(
            "[00:01.00] Line one\n[00:02.00] Line two",
            cleanAiText(raw),
        )
        assertNull(cleanAiText("NOT_FOUND"))
        assertNull(cleanAiText("找不到可靠歌词"))
    }

    @Test
    fun inspectsTimedAndPlainLyrics() {
        val timed = inspectLyrics(
            "[00:01.00] a\n[00:02.00] b\n[00:03.00] c\n[00:04.00] d",
            10_000L,
        )
        assertTrue(timed.timed)
        assertEquals(4, timed.lines)
        assertFalse(timed.beyondDuration)
        val plain = inspectLyrics("a\nb\nc\nd", 10_000L)
        assertFalse(plain.timed)
    }

    @Test
    fun normalizesBaseUrls() {
        assertEquals(
            "https://api.deepseek.com/v1",
            normalizeBaseUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1",
            normalizeBaseUrl("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
        assertNull(normalizeBaseUrl("http://example.com/v1"))
        assertEquals(
            "http://127.0.0.1:8080/v1",
            normalizeBaseUrl("http://127.0.0.1:8080/v1"),
        )
    }

    @Test
    fun parsesLrclibJsonAndCandidatesRoundTrip() {
        val json = JSONObject(
            """
            {
              "id": 17,
              "trackName": "晴天",
              "artistName": "周杰伦",
              "albumName": "叶惠美",
              "duration": 270.0,
              "instrumental": false,
              "syncedLyrics": "[00:29.30] 故事的小黃花",
              "plainLyrics": "故事的小黃花"
            }
            """.trimIndent(),
        )
        val record = LrclibRecord.fromJson(json)
        assertEquals("晴天", record.trackName)
        assertEquals(270.0, record.duration, 0.001)
        assertTrue(record.isSynced())
        val candidate = rankCandidates(
            listOf(record),
            "晴天",
            "周杰伦",
            270.0,
        ).first()
        val restored = candidatesFromJson(candidatesToJson(listOf(candidate)))
        assertEquals(1, restored.size)
        assertEquals("晴天", restored.first().record.trackName)
        assertEquals(candidate.score, restored.first().score, 0.001f)
    }
}
