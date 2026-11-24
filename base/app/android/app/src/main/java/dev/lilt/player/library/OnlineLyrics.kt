package dev.lilt.player.library

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Pure matching/model helpers for the optional online lyrics feature. Network
 * calls live in `data/OnlineLyricsClient.kt`; keeping this file free of Android
 * APIs lets the JVM unit tests cover the fragile parts.
 */

enum class LyricsSource(val wire: String) {
    LOCAL("local"),
    LRCLIB("lrclib"),
    AI("ai"),
    USER("user"),
    ;

    companion object {
        fun fromWire(value: String?): LyricsSource =
            entries.firstOrNull { it.wire == value } ?: LOCAL
    }
}

data class LrclibRecord(
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    /** Seconds, as returned by LRCLIB. */
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val hasWordSync: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    fun text(): String? =
        syncedLyrics?.takeIf { it.isNotBlank() }
            ?: plainLyrics?.takeIf { it.isNotBlank() }

    fun isSynced(): Boolean = !syncedLyrics.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("trackName", trackName)
        put("artistName", artistName)
        put("albumName", albumName)
        put("duration", duration)
        put("instrumental", instrumental)
        put("hasWordSync", hasWordSync)
        put("plainLyrics", plainLyrics ?: JSONObject.NULL)
        put("syncedLyrics", syncedLyrics ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): LrclibRecord = LrclibRecord(
            id = obj.optLong("id", 0L),
            trackName = obj.optString("trackName", ""),
            artistName = obj.optString("artistName", ""),
            albumName = obj.optString("albumName", ""),
            duration = obj.optDouble("duration", 0.0),
            instrumental = obj.optBoolean("instrumental", false),
            hasWordSync = obj.optBoolean("hasWordSync", false),
            plainLyrics = if (obj.isNull("plainLyrics")) {
                null
            } else {
                obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
            },
            syncedLyrics = if (obj.isNull("syncedLyrics")) {
                null
            } else {
                obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
            },
        )
    }
}

data class LyricsCandidate(
    val record: LrclibRecord,
    val score: Float,
    val durationDiff: Double,
    val titleExact: Boolean,
    val artistExact: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("record", record.toJson())
        put("score", score.toDouble())
        put("durationDiff", durationDiff)
        put("titleExact", titleExact)
        put("artistExact", artistExact)
    }

    companion object {
        fun fromJson(obj: JSONObject): LyricsCandidate? {
            val record = obj.optJSONObject("record") ?: return null
            return LyricsCandidate(
                record = LrclibRecord.fromJson(record),
                score = obj.optDouble("score", 0.0).toFloat(),
                durationDiff = obj.optDouble("durationDiff", Double.POSITIVE_INFINITY),
                titleExact = obj.optBoolean("titleExact", false),
                artistExact = obj.optBoolean("artistExact", false),
            )
        }
    }
}

sealed class MatchOutcome {
    data class Best(val candidate: LyricsCandidate) : MatchOutcome()
    data class Ambiguous(val candidates: List<LyricsCandidate>) : MatchOutcome()
    data object Instrumental : MatchOutcome()
    data object None : MatchOutcome()
}

fun candidatesToJson(candidates: List<LyricsCandidate>): String =
    JSONArray().apply { candidates.forEach { put(it.toJson()) } }.toString()

fun candidatesFromJson(text: String?): List<LyricsCandidate> {
    if (text.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(LyricsCandidate::fromJson)
        }
    }.getOrDefault(emptyList())
}

private fun stripBrackets(input: String): String {
    val out = StringBuilder(input.length)
    var depth = 0
    for (ch in input) {
        when (ch) {
            '(', '[', '{', '（', '【', '｛' -> depth++
            ')', ']', '}', '）', '】', '｝' -> depth = (depth - 1).coerceAtLeast(0)
            else -> if (depth == 0) out.append(ch)
        }
    }
    return out.toString()
}

/** Lowercase, drop punctuation and bracketed/live/remaster suffixes. */
fun normalizeMatchText(input: String): String {
    var text = stripBrackets(input).lowercase(Locale.ROOT)
    text = text.replace(Regex("\\s+-\\s+remaster(ed)?.*$"), "")
    text = text.replace(Regex("\\s+remaster(ed)?.*$"), "")
    text = text.replace(Regex("\\s+(feat\\.|ft\\.|featuring)\\s+.*$"), "")
    for (suffix in listOf(
        " - live",
        " live version",
        " album version",
        " - demo",
        " - edit",
    )) {
        if (text.endsWith(suffix)) text = text.dropLast(suffix.length)
    }
    val out = StringBuilder(text.length)
    var lastSpace = false
    for (ch in text) {
        if (ch.isLetterOrDigit() || ch == '\'') {
            out.append(ch)
            lastSpace = false
        } else if (!lastSpace && out.isNotEmpty()) {
            out.append(' ')
            lastSpace = true
        }
    }
    return out.toString().trim()
}

private fun artistTokens(input: String): List<String> {
    var expanded = input.lowercase(Locale.ROOT)
    for (marker in listOf(" feat.", " feat ", " ft.", " ft ", " featuring ")) {
        expanded = expanded.replace(marker, ";")
    }
    return expanded
        .split(';', '/', '&', ',', '、', '|')
        .map(::normalizeMatchText)
        .filter { it.isNotEmpty() && it != "the" }
}

fun artistExact(left: String, right: String): Boolean {
    val leftTokens = artistTokens(left)
    val rightTokens = artistTokens(right)
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
    return rightTokens.any { token ->
        leftTokens.any { other ->
            other == token ||
                (token.length >= 4 && other.length >= 4 &&
                    (other.contains(token) || token.contains(other)))
        }
    }
}

fun titleExact(left: String, right: String): Boolean {
    val a = normalizeMatchText(left)
    val b = normalizeMatchText(right)
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    val shorter = minOf(a.length, b.length)
    return shorter >= 6 && (a.contains(b) || b.contains(a))
}

fun rankCandidates(
    records: List<LrclibRecord>,
    title: String,
    artist: String,
    durationSeconds: Double,
): List<LyricsCandidate> {
    val artistUnknown = artist.isBlank() || normalizeMatchText(artist) == "unknown artist"
    val ranked = mutableListOf<LyricsCandidate>()
    for (record in records) {
        if (record.text() == null && !record.instrumental) continue
        if (!titleExact(title, record.trackName)) continue
        val artistSame = !artistUnknown && artistExact(artist, record.artistName)
        val diff = if (durationSeconds > 1.0 && record.duration > 1.0) {
            kotlin.math.abs(durationSeconds - record.duration)
        } else {
            Double.POSITIVE_INFINITY
        }
        var score = 0.55f
        if (artistSame) score += 0.25f else if (artistUnknown) score += 0.15f
        if (diff <= 2.5) score += 0.20f else if (diff <= 5.0) score += 0.08f
        if (record.isSynced()) score += 0.03f
        if (record.instrumental) score -= 0.25f
        if (!durationSeconds.isFinite() || durationSeconds <= 1.0) score += 0.10f
        ranked += LyricsCandidate(
            record = record,
            score = score.coerceIn(0f, 1f),
            durationDiff = diff,
            titleExact = true,
            artistExact = artistSame || artistUnknown,
        )
    }
    return ranked.sortedWith(
        compareByDescending<LyricsCandidate> { it.score }
            .thenBy { it.durationDiff },
    )
}

fun chooseMatch(rankedInput: List<LyricsCandidate>): MatchOutcome {
    if (rankedInput.isEmpty()) return MatchOutcome.None
    if (rankedInput.none { !it.record.instrumental }) return MatchOutcome.Instrumental
    val ranked = rankedInput.filter { !it.record.instrumental }
    val top = ranked.first()
    val exactEnough = top.titleExact && top.artistExact &&
        (top.durationDiff <= 2.5 || !top.durationDiff.isFinite())
    if (exactEnough && top.score >= 0.90f) return MatchOutcome.Best(top)
    val ambiguous = ranked.filter { it.score >= 0.62f }
    return if (ambiguous.isEmpty()) MatchOutcome.None else MatchOutcome.Ambiguous(ambiguous)
}

fun cleanAiText(raw: String): String? {
    var text = raw.replace("\r\n", "\n").trim()
    if (text.startsWith("```")) {
        text = text.lineSequence().drop(1).joinToString("\n")
        val end = text.lastIndexOf("```")
        if (end >= 0) text = text.substring(0, end)
    }
    val lower = text.lowercase(Locale.ROOT)
    val short = text.lineSequence().count { it.isNotBlank() } <= 3
    if (short && (
            lower.contains("not_found") ||
                lower.contains("not found") ||
                lower.contains("无法找到") ||
                lower.contains("找不到") ||
                lower.contains("cannot find") ||
                lower.contains("unable to find")
            )
    ) {
        return null
    }
    var lines = text.lines()
    val first = lines.firstOrNull()
    if (first != null && first.trim().endsWith(':') &&
        (first.lowercase(Locale.ROOT).contains("lyric") || first.contains("歌词"))
    ) {
        lines = lines.drop(1)
    }
    val cleaned = lines
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains(':')
        }
        .joinToString("\n")
        .trim()
    return cleaned.takeIf { it.isNotEmpty() }
}

data class LrcCheck(
    val lines: Int,
    val timed: Boolean,
    val lastTimeMs: Long,
    val tooFewLines: Boolean,
    val beyondDuration: Boolean,
)

fun inspectLyrics(text: String, durationMs: Long): LrcCheck {
    val parsed = parseLrc(text)
    val lines = parsed.lines.count { it.text.isNotBlank() }
    val last = parsed.lines.maxOfOrNull { it.timeMs } ?: 0L
    return LrcCheck(
        lines = lines,
        timed = parsed.timed,
        lastTimeMs = last,
        tooFewLines = lines < 4,
        beyondDuration = durationMs > 1000L && last > durationMs + 15_000L,
    )
}

/**
 * Strip `/chat/completions`, require HTTPS except for loopback, and add `/v1`
 * when the path has no version segment. Mirrors the desktop implementation.
 */
fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    var value = trimmed
    if (value.lowercase(Locale.ROOT).endsWith("/chat/completions")) {
        value = value.dropLast("/chat/completions".length).trimEnd('/')
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "https" && scheme != "http") return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (scheme == "http" && !(host == "localhost" || host == "::1" || host.startsWith("127."))) {
        return null
    }
    val path = (uri.path ?: "").trimEnd('/')
    val hasVersion = path.split('/').any { segment ->
        segment.length >= 2 &&
            (segment[0] == 'v' || segment[0] == 'V') &&
            segment[1].isDigit()
    }
    val finalPath = if (hasVersion) path else "$path/v1"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "$scheme://$host$port$finalPath"
}

fun isOpencodeGo(provider: String, baseUrl: String): Boolean {
    if (provider == "opencode-go") return true
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
    return uri.host?.equals("opencode.ai", ignoreCase = true) == true &&
        uri.path.orEmpty().contains("/zen/go")
}

data class AiConfig(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    fun normalized(): AiConfig? {
        val base = normalizeBaseUrl(baseUrl) ?: return null
        if (model.isBlank()) return null
        return copy(baseUrl = base, model = model.trim())
    }
}

data class AiProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
)

val AI_PROVIDERS: List<AiProviderPreset> = listOf(
    AiProviderPreset(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com/v1",
        listOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"),
    ),
    AiProviderPreset(
        "glm",
        "智谱 GLM",
        "https://open.bigmodel.cn/api/paas/v4",
        listOf("glm-5.3", "glm-5.3-flash", "glm-5.2", "glm-4.7-flash"),
    ),
    AiProviderPreset(
        "mimo",
        "小米 MiMo",
        "https://api.xiaomimimo.com/v1",
        listOf("mimo-v2.5-pro", "mimo-v2.5"),
    ),
    AiProviderPreset(
        "openai",
        "OpenAI GPT",
        "https://api.openai.com/v1",
        listOf("gpt-5.1", "gpt-5", "gpt-4.1", "gpt-4o", "gpt-4o-mini"),
    ),
    AiProviderPreset(
        "opencode-go",
        "OpenCode Go",
        "https://opencode.ai/zen/go/v1",
        listOf(
            "glm-5.3",
            "glm-5.3-flash",
            "glm-5.2",
            "grok-4.6",
            "grok-4.7",
            "gpt-6-luna",
            "gpt-5.6-luna",
            "claude-haiku-5-5",
            "kimi-k3",
            "kimi-k2.7-code",
            "minimax-m3",
            "minimax-m2.7",
            "mimo-v2.6-pro",
            "qwen3.7-max",
            "qwen3.8-max",
            "deepseek-v4-pro",
            "deepseek-v4.1-flash",
        ),
    ),
    AiProviderPreset(
        "opencode-zen",
        "OpenCode Zen",
        "https://opencode.ai/zen/v1",
        listOf(
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "mimo-v2.6-flash-free",
            "minimax-m3.1-flash-free",
            "longcat-2.5-free",
            "nemotron-3.5-lightning-free",
            "nemotron-3.5-super-vl-free",
            "big-pickle",
        ),
    ),
    AiProviderPreset(
        "kimi",
        "Kimi 月之暗面",
        "https://api.moonshot.cn/v1",
        listOf("kimi-k2.5", "kimi-k2-turbo-preview", "moonshot-v1-128k", "moonshot-v1-32k"),
    ),
    AiProviderPreset(
        "qwen",
        "通义千问 Qwen",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf("qwen3-max", "qwen3.5-plus", "qwen3.5-turbo", "qwen-plus", "qwen-turbo"),
    ),
    AiProviderPreset(
        "siliconflow",
        "SiliconFlow 硅基流动",
        "https://api.siliconflow.cn/v1",
        listOf(
            "deepseek-ai/DeepSeek-V3.2",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "moonshotai/Kimi-K2-Instruct-0905",
        ),
    ),
    AiProviderPreset("custom", "自定义（OpenAI 兼容）", "", emptyList()),
)

fun findAiProvider(id: String): AiProviderPreset =
    AI_PROVIDERS.firstOrNull { it.id == id } ?: AI_PROVIDERS.first()

fun lrclibSearchUrl(
    title: String,
    artist: String,
    album: String,
    queryOnly: String? = null,
): String {
    val base = "https://lrclib.net/api/search"
    if (queryOnly != null) {
        return "$base?q=${encodeUrl(queryOnly)}"
    }
    val parts = mutableListOf("track_name=${encodeUrl(title)}")
    if (artist.isNotBlank() && !artist.equals("unknown artist", ignoreCase = true)) {
        parts += "artist_name=${encodeUrl(artist)}"
    }
    if (album.isNotBlank()) parts += "album_name=${encodeUrl(album)}"
    return "$base?${parts.joinToString("&")}"
}

private fun encodeUrl(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
