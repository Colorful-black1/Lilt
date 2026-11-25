package dev.lilt.player.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Public GitHub release metadata used by the manual update check. */
sealed interface UpdateCheckResult {
    data class Available(
        val version: String,
        val releaseUrl: String,
        val notes: String,
        val apkUrl: String?,
    ) : UpdateCheckResult

    data class UpToDate(val latest: String) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object UpdateChecker {
    const val REPO = "Colorful-black1/Lilt"
    const val API_URL = "https://api.github.com/repos/Colorful-black1/Lilt/releases/latest"
    const val RELEASES_URL = "https://github.com/Colorful-black1/Lilt/releases"

    fun fetchLatestRelease(currentVersion: String, userAgent: String): UpdateCheckResult {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return UpdateCheckResult.NoRelease
            if (status !in 200..299) {
                return UpdateCheckResult.Error("GitHub returned HTTP $status.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val latest = json.optString("tag_name").trim().trimStart('v', 'V')
            if (latest.isBlank()) return UpdateCheckResult.Error("The latest release has no tag.")
            if (!isNewerVersion(latest, currentVersion)) {
                return UpdateCheckResult.UpToDate(latest)
            }
            val releaseUrl = json.optString("html_url").ifBlank { RELEASES_URL }
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }
            return UpdateCheckResult.Available(
                version = latest,
                releaseUrl = releaseUrl,
                notes = json.optString("body"),
                apkUrl = apkUrl,
            )
        } catch (error: Exception) {
            return UpdateCheckResult.Error(error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the release APK into files/updates. The file is later exposed
     * through FileProvider and handed to the system installer.
     */
    fun downloadApk(
        context: Context,
        url: String,
        version: String,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): File {
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(directory, "Lilt-${safeVersion(version)}.apk")
        val part = File(directory, "Lilt-${safeVersion(version)}.part")
        part.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty(
                "User-Agent",
                "Lilt/${currentVersion(context)} (https://github.com/$REPO)",
            )
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Download failed with HTTP $status.")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 150) {
                            lastReport = now
                            onProgress(received, total)
                        }
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("Could not finish the APK download.")
            onProgress(received, total ?: received)
            return target
        } catch (error: Exception) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    private fun safeVersion(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.ifBlank { "latest" }

    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = versionParts(latest) ?: return false
        val currentParts = versionParts(current) ?: return false
        val size = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until size) {
            val a = latestParts.getOrElse(index) { 0 }
            val b = currentParts.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun versionParts(value: String): List<Int>? {
        val core = value.trim().trimStart('v', 'V').split('-', '+').firstOrNull()?.trim()
            ?: return null
        if (core.isBlank()) return null
        return core.split('.').map { part -> part.toIntOrNull() ?: return null }
    }
}
