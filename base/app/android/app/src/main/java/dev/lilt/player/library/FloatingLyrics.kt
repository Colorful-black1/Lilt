package dev.lilt.player.library

import kotlin.math.roundToInt

/** The three lyric lines shown by the floating overlay. */
data class FloatingLyricLines(
    val before: String,
    val active: String,
    val after: String,
    /**
     * Timed line index that is currently active, or -1 before the first tag.
     * Plain lyrics use 0 so the first line gets the normal emphasis.
     */
    val activeIndex: Int,
)

/**
 * Picks the line window around the current position. Blank documents return
 * empty strings; plain text shows the first two lines without timing.
 */
fun floatingLyricLines(
    document: LrcDocument?,
    positionMs: Long,
    offsetMs: Int,
): FloatingLyricLines {
    val lines = document?.lines.orEmpty()
    if (lines.isEmpty()) return FloatingLyricLines("", "", "", -1)
    if (document?.timed != true) {
        return FloatingLyricLines(
            before = "",
            active = lines.getOrNull(0)?.text.orEmpty(),
            after = lines.getOrNull(1)?.text.orEmpty(),
            activeIndex = 0,
        )
    }
    val raw = activeLrcLine(document, positionMs, offsetMs)
    val center = if (raw >= 0) raw else 0
    return FloatingLyricLines(
        before = lines.getOrNull(center - 1)?.text.orEmpty(),
        active = lines[center].text,
        after = lines.getOrNull(center + 1)?.text.orEmpty(),
        activeIndex = raw,
    )
}

/** Floating lyric sizes use half-point steps between 18 and 44 sp. */
fun clampFloatingFontSize(value: Float): Float =
    ((value.coerceIn(18f, 44f) * 2f).roundToInt() / 2f)

/** The five named accents, as opaque ARGB values for native overlay views. */
fun accentArgb(id: String): Int = when (id) {
    "sage" -> 0xFF9BC49A.toInt()
    "sky" -> 0xFF8FB7F0.toInt()
    "rose" -> 0xFFE6A0B4.toInt()
    "violet" -> 0xFFB4A4F4.toInt()
    else -> 0xFFF2B46B.toInt()
}

/** Widget progress uses a 0..1000 range so RemoteViews never overflows. */
fun widgetProgress(positionMs: Long, durationMs: Long): Int {
    if (durationMs <= 0L) return 0
    return ((positionMs.coerceAtLeast(0L).toDouble() / durationMs) * 1000.0)
        .roundToInt()
        .coerceIn(0, 1000)
}

/** `1:23 / 4:05`, or an empty string while the duration is unknown. */
fun widgetTimeLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return ""
    return "${clockLabel(positionMs)} / ${clockLabel(durationMs)}"
}

fun clockLabel(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
