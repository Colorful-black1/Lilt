package dev.lilt.player

import dev.lilt.player.data.OnlineLyricsClient
import dev.lilt.player.library.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class OnlineLyricsClientTest {
    private fun serveOnce(
        response: String,
        request: ArrayBlockingQueue<String>,
    ): Int {
        val server = ServerSocket(0)
        Thread {
            repeat(3) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val headerBytes = java.io.ByteArrayOutputStream()
                        var matched = 0
                        while (true) {
                            val value = input.read()
                            if (value < 0) break
                            headerBytes.write(value)
                            matched = when {
                                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                                else -> 0
                            }
                            if (matched == 4) break
                        }
                        val header = headerBytes.toString(Charsets.UTF_8.name())
                        val length = Regex("(?i)content-length:\\s*(\\d+)")
                            .find(header)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0
                        val body = if (length > 0) {
                            val bytes = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val count = input.read(bytes, read, length - read)
                                if (count <= 0) break
                                read += count
                            }
                            String(bytes, 0, read, Charsets.UTF_8)
                        } else {
                            ""
                        }
                        request.offer(header + body, 1, TimeUnit.SECONDS)
                        val bodyBytes = response.toByteArray()
                        val responseHeader = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(responseHeader.toByteArray())
                            write(bodyBytes)
                            flush()
                        }
                    }
                }.onFailure { System.err.println("test server: $it") }
            }
            server.close()
        }.start()
        return server.localPort
    }

    private fun config(port: Int) = AiConfig(
        provider = "custom",
        baseUrl = "http://127.0.0.1:$port/v1",
        model = "stub-model",
        apiKey = "sk-test",
    )

    @Test
    fun fetchesModelsAndLyricsFromAnOpenAiCompatibleEndpoint() {
        val modelRequest = ArrayBlockingQueue<String>(1)
        val modelPort = serveOnce(
            """{"data":[{"id":"model-b"},{"id":"model-a"}]}""",
            modelRequest,
        )
        val models = OnlineLyricsClient.fetchModels(config(modelPort))
        assertEquals(listOf("model-a", "model-b"), models)
        val modelHeaders = modelRequest.poll(3, TimeUnit.SECONDS).orEmpty()
        assertTrue(modelHeaders.contains("GET /v1/models"))
        assertTrue(modelHeaders.lowercase().contains("authorization: bearer sk-test"))

        val chatRequest = ArrayBlockingQueue<String>(1)
        val chatPort = serveOnce(
            """{"choices":[{"message":{"content":"```lrc\n[00:01.00] Line one\n[00:02.00] Line two\n```"}}]}""",
            chatRequest,
        )
        val lyrics = OnlineLyricsClient.fetchAiLyrics(
            config = config(chatPort),
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 120_000L,
        )
        assertTrue(lyrics.orEmpty().contains("[00:01.00] Line one"))
        val chatText = chatRequest.poll(3, TimeUnit.SECONDS).orEmpty()
        assertTrue(chatText.contains("POST /v1/chat/completions"))
        assertTrue(chatText.contains("Test Song"))
    }
}
