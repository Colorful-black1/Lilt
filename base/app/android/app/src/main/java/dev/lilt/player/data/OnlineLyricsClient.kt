package dev.lilt.player.data

import dev.lilt.player.library.AiConfig
import dev.lilt.player.library.LrclibRecord
import dev.lilt.player.library.cleanAiText
import dev.lilt.player.library.isOpencodeGo
import dev.lilt.player.library.lrclibSearchUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Minimal HTTP client for the optional online lyrics feature. Android's
 * HttpURLConnection is used so the app does not pull in OkHttp/Retrofit.
 * Every call is blocking; callers must run it on Dispatchers.IO.
 */
object OnlineLyricsClient {
    private const val USER_AGENT = "Lilt/1.0 (https://github.com/Colorful-black1/Lilt)"
    private const val LRCLIB_LIMIT = 2 * 1024 * 1024
    private const val AI_LIMIT = 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val AI_READ_TIMEOUT_MS = 90_000
    private val session = UUID.randomUUID().toString()

    class OnlineLyricsException(message: String) : Exception(message)

    fun searchLrclib(
        title: String,
        artist: String,
        album: String,
    ): List<LrclibRecord> {
        if (title.isBlank()) return emptyList()
        val first = requestJson(
            url = lrclibSearchUrl(title, artist, album),
            method = "GET",
            headers = lrclibHeaders(),
            body = null,
            limit = LRCLIB_LIMIT,
            timeout = READ_TIMEOUT_MS,
        )
        var records = parseLrclib(first)
        if (records.isEmpty()) {
            Thread.sleep(350)
            val query = listOf(title.trim(), artist.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val second = requestJson(
                url = lrclibSearchUrl(title, artist, album, queryOnly = query),
                method = "GET",
                headers = lrclibHeaders(),
                body = null,
                limit = LRCLIB_LIMIT,
                timeout = READ_TIMEOUT_MS,
            )
            records = parseLrclib(second)
        }
        return records
    }

    fun fetchModels(config: AiConfig): List<String> {
        val normalized = config.normalized()
            ?: throw OnlineLyricsException("接口地址或模型无效")
        val text = aiRequest(normalized, "models", null, READ_TIMEOUT_MS)
        val root = runCatching { JSONObject(text) }.getOrNull()
        val array = root?.optJSONArray("data")
            ?: root?.optJSONArray("models")
            ?: runCatching { JSONArray(text) }.getOrNull()
            ?: throw OnlineLyricsException("模型列表不是合法 JSON")
        val models = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id").ifEmpty {
                    item.optString("name").ifEmpty { item.optString("model") }
                }
                else -> ""
            }.trim().removePrefix("models/")
            if (id.isNotEmpty()) models += id
            if (models.size >= 300) break
        }
        if (models.isEmpty()) throw OnlineLyricsException("未获取到任何模型")
        return models.sorted()
    }

    fun testAi(config: AiConfig): String {
        val normalized = config.normalized()
            ?: throw OnlineLyricsException("接口地址或模型无效")
        // Listing models only proves authentication. Also send a tiny chat
        // request so a broken generation endpoint is not reported as success.
        val models = runCatching { fetchModels(normalized) }
        val text = complete(
            normalized,
            "You are a connection test. Reply with exactly OK.",
            "OK",
            READ_TIMEOUT_MS,
        )
        val line = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isEmpty()) throw OnlineLyricsException("AI 未返回内容")
        return models.getOrNull()?.let { "连接成功 · ${it.size} 个模型 · $line" }
            ?: "连接成功 · $line"
    }

    fun fetchAiLyrics(
        config: AiConfig,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): String? {
        val normalized = config.normalized()
            ?: throw OnlineLyricsException("接口地址或模型无效")
        val minutes = if (durationMs > 0L) {
            val total = durationMs / 1000L
            "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
        } else {
            "unknown"
        }
        val system = "You prepare a draft LRC file for a user who owns a local copy " +
            "of the recording. Write the lyric lines you know from your own knowledge, " +
            "each prefixed with an approximate [mm:ss.xx] timestamp. The user will review " +
            "and edit the draft before saving, so approximate timing is acceptable; never " +
            "return NOT_FOUND just because the exact timing is uncertain. Return only the " +
            "LRC lines. Do not add a title, translation, notes or explanation. If you do " +
            "not know this song at all, return exactly NOT_FOUND."
        val user = "Title: $title\nArtist: $artist\nAlbum: $album\nDuration: $minutes\n\n" +
            "The user owns a local copy. Return draft LRC lyrics, or NOT_FOUND."
        val cleaned = cleanAiText(complete(normalized, system, user, AI_READ_TIMEOUT_MS))
            ?: return null
        if (looksLikeRefusal(cleaned)) {
            throw OnlineLyricsException("AI 供应商拒绝提供歌词（可能受版权限制）")
        }
        return cleaned
    }

    private fun looksLikeRefusal(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val short = text.lineSequence().count { it.isNotBlank() } <= 3
        return short && listOf(
            "i can't provide",
            "i cannot provide",
            "i can't help with",
            "i cannot help with",
            "unable to provide",
            "can't provide lyrics",
            "cannot provide lyrics",
            "cannot reproduce",
            "can't reproduce",
            "copyright",
            "版权",
            "无法提供",
            "不能提供",
            "抱歉，我",
        ).any { lower.contains(it) }
    }

    private fun complete(
        config: AiConfig,
        system: String,
        user: String,
        timeout: Int,
    ): String {
        val body = JSONObject().apply {
            put("model", config.model)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                },
            )
            put("temperature", 0)
            put("stream", false)
        }.toString()
        val text = aiRequest(config, "chat/completions", body, timeout)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw OnlineLyricsException("AI 响应不是合法 JSON")
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifEmpty { "AI 请求失败" }
            throw OnlineLyricsException(message)
        }
        val choices = root.optJSONArray("choices")
            ?: throw OnlineLyricsException("AI 未返回内容")
        val choice = choices.optJSONObject(0)
            ?: throw OnlineLyricsException("AI 未返回内容")
        choice.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val content = choice.optJSONObject("message")?.opt("content")
        when (content) {
            is String -> if (content.isNotBlank()) return content
            is JSONArray -> {
                val joined = buildString {
                    for (index in 0 until content.length()) {
                        val part = content.opt(index)
                        when (part) {
                            is String -> append(part)
                            is JSONObject -> append(part.optString("text"))
                        }
                    }
                }
                if (joined.isNotBlank()) return joined
            }
        }
        throw OnlineLyricsException("AI 未返回内容")
    }

    private fun aiRequest(
        config: AiConfig,
        path: String,
        body: String?,
        timeout: Int,
    ): String {
        val base = config.baseUrl.trimEnd('/')
        val url = "$base/${path.trimStart('/')}"
        val headers = mutableListOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Authorization" to "Bearer ${config.apiKey}",
        )
        if (isOpencodeGo(config.provider, base)) {
            headers += "x-opencode-session" to session
        }
        if (body != null) headers += "Content-Type" to "application/json"
        return try {
            requestJson(
                url = url,
                method = if (body == null) "GET" else "POST",
                headers = headers,
                body = body,
                limit = AI_LIMIT,
                timeout = timeout,
            )
        } catch (error: OnlineLyricsException) {
            throw OnlineLyricsException(error.message.orEmpty().replace(config.apiKey, "***"))
        }
    }

    private fun lrclibHeaders(): List<Pair<String, String>> = listOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
    )

    private fun parseLrclib(text: String): List<LrclibRecord> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(LrclibRecord::fromJson)
        }
    }

    private fun requestJson(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: String?,
        limit: Int,
        timeout: Int,
    ): String {
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeout
                instanceFollowRedirects = true
                useCaches = false
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code == 429) {
                val retry = conn.getHeaderField("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.coerceIn(1L, 120L)
                    ?: 30L
                throw OnlineLyricsException("请求频率受限，请 $retry 秒后重试")
            }
            val stream: InputStream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: throw OnlineLyricsException("HTTP $code")
            }
            val text = readLimited(stream, limit)
            if (code !in 200..299) {
                val detail = text.trim().take(240)
                throw OnlineLyricsException(
                    if (detail.isEmpty()) "HTTP $code" else "HTTP $code: $detail",
                )
            }
            return text
        } catch (error: OnlineLyricsException) {
            throw error
        } catch (error: Exception) {
            throw OnlineLyricsException(
                "无法连接服务：${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): String {
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val take = minOf(read, limit - total)
                if (take > 0) output.write(buffer, 0, take)
                total += read
                if (total >= limit) break
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
