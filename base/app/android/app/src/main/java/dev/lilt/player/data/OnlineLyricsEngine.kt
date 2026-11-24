package dev.lilt.player.data

import android.content.Context
import dev.lilt.player.library.LyricsSource
import dev.lilt.player.library.MatchOutcome
import dev.lilt.player.library.candidatesToJson
import dev.lilt.player.library.chooseMatch
import dev.lilt.player.library.rankCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One LRCLIB lookup, shared by the playback service (automatic first play) and
 * the UI (manual retry). The result is written to Room; callers only need to
 * observe the lyrics/fetch-state flows.
 */
object OnlineLyricsEngine {
    private const val NOT_FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val INSTRUMENTAL_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val AMBIGUOUS_TTL_MS = 24L * 60 * 60 * 1000
    private const val ERROR_TTL_MS = 10L * 60 * 1000
    private const val STALE_FETCH_MS = 2L * 60 * 1000

    suspend fun fetchLrclib(
        context: Context,
        mediaId: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (mediaId <= 0 || title.isBlank()) return@withContext
        val db = LiltDatabase.get(context)
        val lyricsDao = db.lyricsDao()
        val stateDao = db.lyricsFetchDao()

        val existing = lyricsDao.get(mediaId)
        if (existing != null && existing.text.isNotBlank()) return@withContext

        val now = System.currentTimeMillis()
        val previous = stateDao.get(mediaId)
        if (!force && previous?.autoDisabled == true) return@withContext
        if (!force && previous != null && previous.nextRetryAt > now) return@withContext
        if (!force && previous != null &&
            previous.status == LyricsFetchStatus.FETCHING &&
            now - previous.lastAttemptAt < STALE_FETCH_MS
        ) {
            return@withContext
        }

        stateDao.put(
            LyricsFetchEntity(
                mediaId = mediaId,
                status = LyricsFetchStatus.FETCHING,
                lastAttemptAt = now,
                nextRetryAt = 0L,
                autoDisabled = false,
            ),
        )

        try {
            val records = OnlineLyricsClient.searchLrclib(title, artist, album)
            val ranked = rankCandidates(
                records = records,
                title = title,
                artist = artist,
                durationSeconds = durationMs / 1000.0,
            )
            when (val outcome = chooseMatch(ranked)) {
                is MatchOutcome.Best -> {
                    val text = outcome.candidate.record.text()
                    if (text.isNullOrBlank()) {
                        stateDao.put(
                            LyricsFetchEntity(
                                mediaId = mediaId,
                                status = LyricsFetchStatus.NOT_FOUND,
                                lastAttemptAt = now,
                                nextRetryAt = now + NOT_FOUND_TTL_MS,
                            ),
                        )
                    } else {
                        lyricsDao.put(
                            LyricsEntity(
                                mediaId = mediaId,
                                text = text.trim(),
                                offsetMs = existing?.offsetMs ?: 0,
                                source = LyricsSource.LRCLIB.wire,
                                fetchedAt = now,
                                updatedAt = now,
                            ),
                        )
                        stateDao.delete(mediaId)
                    }
                }
                is MatchOutcome.Ambiguous -> {
                    stateDao.put(
                        LyricsFetchEntity(
                            mediaId = mediaId,
                            status = LyricsFetchStatus.AMBIGUOUS,
                            lastAttemptAt = now,
                            nextRetryAt = now + AMBIGUOUS_TTL_MS,
                            candidateCount = outcome.candidates.size,
                            candidates = candidatesToJson(outcome.candidates),
                        ),
                    )
                }
                MatchOutcome.Instrumental -> {
                    stateDao.put(
                        LyricsFetchEntity(
                            mediaId = mediaId,
                            status = LyricsFetchStatus.INSTRUMENTAL,
                            lastAttemptAt = now,
                            nextRetryAt = now + INSTRUMENTAL_TTL_MS,
                        ),
                    )
                }
                MatchOutcome.None -> {
                    stateDao.put(
                        LyricsFetchEntity(
                            mediaId = mediaId,
                            status = LyricsFetchStatus.NOT_FOUND,
                            lastAttemptAt = now,
                            nextRetryAt = now + NOT_FOUND_TTL_MS,
                        ),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = (error.message ?: "获取歌词失败").take(240)
            stateDao.put(
                LyricsFetchEntity(
                    mediaId = mediaId,
                    status = LyricsFetchStatus.ERROR,
                    lastAttemptAt = now,
                    nextRetryAt = now + ERROR_TTL_MS,
                    error = message,
                ),
            )
        }
    }

    suspend fun clearState(context: Context, mediaId: Long) = withContext(Dispatchers.IO) {
        LiltDatabase.get(context).lyricsFetchDao().delete(mediaId)
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        LiltDatabase.get(context).lyricsFetchDao().clear()
    }
}
