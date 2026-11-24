package dev.lilt.player.library

/**
 * Filename-derived tags. Only used when MediaStore reports a blank or
 * placeholder title/artist, so real tags and user overrides always win.
 */
data class FilenameTags(
    val track: Int = 0,
    val title: String,
    val artist: String? = null,
)

data class ScannedTags(
    val title: String,
    val artist: String,
    val track: Int,
)

private val DOUBLE_SPACE = Regex("\\s+")
private val DASHES = charArrayOf('-', '–', '—', '－')
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "m4b", "wma", "aiff", "aif", "alac", "mka",
)

fun parseFilenameTags(displayName: String): FilenameTags {
    val stem = stemOf(displayName)
    val (track, rest) = stripTrackPrefix(stem)
    val (artist, title) = splitArtistTitle(rest)
    val cleanedTitle = cleanTitle(title).ifBlank { cleanTitle(stem) }.ifBlank { "Untitled" }
    return FilenameTags(track = track, title = cleanedTitle, artist = artist)
}

/**
 * Applies the conservative filename fallback to a MediaStore row.
 *
 * `<unknown>` and `Unknown artist` are MediaStore/older-tag placeholders, not
 * real artist names. A title that is identical to the filename is also treated
 * as a fallback, not as a real tag.
 */
fun resolveScannedTags(
    displayName: String,
    rawTitle: String?,
    rawArtist: String?,
    rawTrack: Int,
): ScannedTags {
    val stem = stemOf(displayName)
    val guess = parseFilenameTags(displayName)
    val title = rawTitle?.trim().orEmpty()
    val artist = rawArtist?.trim().orEmpty()
    val titleMissing = title.isEmpty() ||
        title.equals("Untitled", ignoreCase = true) ||
        title.equals("<unknown>", ignoreCase = true) ||
        title.equals(stem, ignoreCase = true) ||
        title.equals(displayName, ignoreCase = true)
    val artistMissing = artist.isEmpty() ||
        artist.equals("<unknown>", ignoreCase = true) ||
        artist.equals("Unknown artist", ignoreCase = true)
    return ScannedTags(
        title = if (titleMissing) guess.title else title,
        artist = if (artistMissing) guess.artist ?: "Unknown artist" else artist,
        track = if (rawTrack > 0) rawTrack else guess.track,
    )
}

private fun stripTrackPrefix(stem: String): Pair<Int, String> {
    val text = stem.trimStart()
    if (text.isEmpty()) return 0 to stem
    val bracket = text.startsWith("(")
    val body = if (bracket) text.substring(1) else text
    val digits = body.takeWhile { it in '0'..'9' }
    if (digits.isEmpty() || digits.length > 3) return 0 to stem
    if (bracket) {
        val close = digits.length
        if (close >= body.length || body[close] != ')') return 0 to stem
        val rest = body.substring(close + 1).trimStart()
        if (rest.isEmpty()) return 0 to stem
        return (digits.toIntOrNull() ?: 0) to rest
    }
    if (digits.length >= body.length) return 0 to stem
    val first = body[digits.length]
    val punctuation = first in charArrayOf('-', '.', '_', ')', ']', '、')
    val whitespace = first.isWhitespace()
    if (!punctuation && !whitespace) return 0 to stem
    // `7 Rings` and `1984` are titles; accept a space-only separator only for
    // zero-padded numbers such as `01 Song`.
    if (whitespace && !digits.startsWith('0')) return 0 to stem
    val rest = body.substring(digits.length).trimStart { it.isWhitespace() || it in "-._)]、" }
    if (rest.isEmpty()) return 0 to stem
    return (digits.toIntOrNull() ?: 0) to rest
}

private fun splitArtistTitle(text: String): Pair<String?, String> {
    val cleaned = cleanTitle(text)
    var bestIndex = -1
    var bestLength = 0
    for (separator in listOf(" - ", " – ", " — ", " － ")) {
        val index = cleaned.indexOf(separator)
        if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
            bestIndex = index
            bestLength = separator.length
        }
    }
    if (bestIndex >= 0) {
        val artist = validArtist(cleaned.substring(0, bestIndex))
        val title = cleanTitle(cleaned.substring(bestIndex + bestLength))
        if (artist != null && title.isNotEmpty()) return artist to title
    }
    // No spaced separator: take the first dash that leaves a usable artist.
    for ((index, ch) in cleaned.withIndex()) {
        if (ch !in DASHES) continue
        val artist = validArtist(cleaned.substring(0, index))
        val title = cleanTitle(cleaned.substring(index + 1))
        if (artist != null && title.isNotEmpty()) return artist to title
    }
    return null to cleaned
}

private fun validArtist(text: String): String? {
    val artist = cleanTitle(text).trim(' ', '-', '–', '—', '－')
    if (artist.isEmpty()) return null
    if (artist.all { it in '0'..'9' }) return null
    return artist
}

private fun cleanTitle(text: String): String =
    text.replace('_', ' ').trim().replace(DOUBLE_SPACE, " ")

private fun stemOf(displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    if (dot > 0) {
        val extension = displayName.substring(dot + 1).lowercase()
        if (extension in AUDIO_EXTENSIONS) return displayName.substring(0, dot)
    }
    return displayName
}
