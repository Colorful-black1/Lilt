package dev.lilt.player.library

import kotlin.math.abs
import kotlin.math.log10

const val EQ_BANDS = 10
val EQ_FREQUENCIES = floatArrayOf(
    31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
)

enum class PreampMode(val id: String) {
    Off("off"),
    Headroom("headroom"),
    Boost("boost"),
    ;

    companion object {
        fun fromId(id: String?): PreampMode =
            entries.firstOrNull { it.id == id } ?: Off
    }
}

data class EqProfile(
    val enabled: Boolean = true,
    val gains: List<Float> = List(EQ_BANDS) { 0f },
    val preset: String = "Flat",
    val preampMode: PreampMode = PreampMode.Off,
    val headroomDb: Float = 6f,
    val boostDb: Float = 2f,
    val bass: Float = 0f,
    val virtualizer: Float = 0f,
    val balance: Float = 100f,
) {
    fun cleaned(): EqProfile {
        val normalized = if (gains.size == EQ_BANDS) gains else List(EQ_BANDS) { index ->
            gains.getOrElse(index) { 0f }
        }
        return copy(
            gains = normalized.map { it.coerceIn(-15f, 15f) },
            preampMode = preampMode,
            headroomDb = headroomDb.coerceIn(1f, 30f),
            boostDb = boostDb.coerceIn(1f, 9f),
            bass = bass.coerceIn(0f, 1f),
            virtualizer = virtualizer.coerceIn(0f, 1f),
            balance = balance.coerceIn(0f, 200f),
            preset = preset.ifBlank { "Flat" },
        )
    }

    /** Effective preamp in dB. Mirrors the desktop build's Headroom/Boost modes. */
    fun preampDb(): Float = when (preampMode) {
        PreampMode.Off -> 0f
        PreampMode.Headroom -> -headroomDb.coerceIn(1f, 30f)
        PreampMode.Boost -> boostDb.coerceIn(1f, 9f)
    }

    /**
     * Peak-based headroom suggestion. Bass contributes up to +15 dB below
     * 200 Hz, mid bands are taken 2 dB down, treble 4 dB down, and 6 dB of
     * the range stay free.
     */
    fun suggestedHeadroomDb(): Float? {
        val bassDb = bass.coerceIn(0f, 1f) * 15f
        var peak = Float.NEGATIVE_INFINITY
        gains.forEachIndexed { index, gain ->
            val frequency = EQ_FREQUENCIES[index]
            val adjusted = when {
                frequency < 200f -> gain + bassDb
                frequency < 3200f -> gain - 2f
                else -> gain - 4f
            }
            if (adjusted > peak) peak = adjusted
        }
        return if (peak > 6f) (peak - 6f).coerceIn(0f, 30f) else null
    }

    /** Kept as a short stable string for one profile per output device. */
    fun encode(): String = listOf(
        PROFILE_VERSION,
        if (enabled) "1" else "0",
        gains.joinToString(","),
        preampMode.id,
        headroomDb.toString(),
        boostDb.toString(),
        bass.toString(),
        virtualizer.toString(),
        balance.toString(),
        preset,
    ).joinToString("|")

    fun withPreset(name: String): EqProfile {
        val value = EQ_PRESETS[name] ?: return this
        return copy(gains = value, preset = name)
    }

    companion object {
        private const val PROFILE_VERSION = "1"

        fun decode(text: String?): EqProfile? {
            if (text.isNullOrBlank()) return null
            val parts = text.split('|')
            if (parts.size < 10 || parts[0] != PROFILE_VERSION) return null
            val gains = parts[2].split(',').mapNotNull { it.toFloatOrNull() }
            if (gains.size != EQ_BANDS) return null
            return EqProfile(
                enabled = parts[1] == "1",
                gains = gains,
                preset = parts[9],
                preampMode = PreampMode.fromId(parts[3]),
                headroomDb = parts[4].toFloatOrNull() ?: 6f,
                boostDb = parts[5].toFloatOrNull() ?: 2f,
                bass = parts[6].toFloatOrNull() ?: 0f,
                virtualizer = parts[7].toFloatOrNull() ?: 0f,
                balance = parts[8].toFloatOrNull() ?: 100f,
            ).cleaned()
        }
    }
}

/** Extra low-frequency gain for the bass slider, in dB. */
fun bassBoostDb(frequency: Float, bass: Float): Float {
    val strength = bass.coerceIn(0f, 1f) * 15f
    return when {
        frequency < 200f -> strength
        frequency < 300f -> strength * 0.30f
        else -> 0f
    }
}

/**
 * Balance is 0 (full left) to 200 (full right) with 100 in the centre.
 * One side stays at full level and the other is attenuated, matching the
 * desktop build.
 */
fun balanceOffsetsDb(balance: Float): Pair<Float, Float> {
    val value = balance.coerceIn(0f, 200f)
    val left = ((200f - value) / 100f).coerceIn(0f, 1f)
    val right = (value / 100f).coerceIn(0f, 1f)
    fun db(level: Float): Float =
        if (level <= 0.0001f) -60f else (20f * log10(level)).coerceIn(-60f, 0f)
    return db(left) to db(right)
}

val EQ_PRESETS: Map<String, List<Float>> = linkedMapOf(
    "Flat" to List(EQ_BANDS) { 0f },
    "Bass" to listOf(6f, 6f, 4.49f, 2.29f, 0.70f, -3f, -2.4f, -1.5f, -1.5f, -1.5f),
    "Mid" to listOf(-1.5f, -1.5f, -0.60f, 1.10f, 3f, 6f, 4f, 0.90f, -0.10f, -1.5f),
    "Treble" to listOf(-1.5f, -1.5f, -1.5f, -1.5f, -2.10f, -2.80f, -0.60f, 3.10f, 4.50f, 6f),
    "Bass and Treble" to
        listOf(6f, 6f, 4.50f, 1.50f, -0.20f, -3f, -1.20f, 1.70f, 4f, 6f),
    "Full Bass" to listOf(10f, 10f, 8f, 4.30f, 0.50f, -6f, -5.40f, -4.40f, -4f, -3f),
    "Full Mid" to listOf(-3f, -3f, -1.20f, 2.20f, 5.10f, 9.80f, 6.80f, 1.80f, -0.10f, -3f),
    "Full Treble" to
        listOf(-3f, -3f, -3.50f, -4.50f, -5.10f, -5.70f, -1.80f, 4.70f, 6.80f, 10f),
    "Reduced Bass" to listOf(-10f, -10f, -8f, -4.40f, -2.80f, 0f, 0f, 0f, 0f, 0f),
    "Reduced Mid" to
        listOf(0f, 0f, -1.60f, -4.60f, -6.60f, -10f, -7.80f, -4.30f, -2.60f, 0f),
    "Reduced Treble" to listOf(0f, 0f, 0f, 0f, 0f, -0.10f, -1.80f, -4.70f, -6.80f, -10f),
    "No Bass" to listOf(-15f, -15f, -12.80f, -8.80f, -5.50f, 0f, 0f, 0f, 0f, 0f),
    "No Mid" to
        listOf(0f, 0f, -3.30f, -9.10f, -11.30f, -15f, -12.60f, -8.60f, -5.20f, 0f),
    "No Treble" to listOf(0f, 0f, 0f, 0f, 0f, -0.30f, -3.60f, -9.20f, -12f, -15f),
    "Classic" to listOf(0f, 0f, 0f, 0f, 0f, -0.10f, -1.20f, -3f, -3.50f, -4.10f),
    "Dance" to listOf(5.80f, 3.20f, 2.10f, 0f, 0f, -2.70f, -2f, -2.20f, -0.60f, 0.10f),
    "Live" to listOf(-4.50f, -3.80f, 2.20f, 2.20f, 2.20f, 2.20f, 2.10f, 1.50f, 1.50f, 1.50f),
    "Speaker" to listOf(-9f, -8.90f, -6.30f, -1.40f, 0.20f, 3f, 3f, 3f, 3f, 3f),
    "Pop" to listOf(1.50f, 4.50f, 5.80f, 3f, 1.50f, 0f, 0f, 0f, 1.50f, 3f),
    "Rock" to listOf(5.80f, 3.20f, 1.30f, -3f, -2.30f, 2.30f, 3.60f, 5.80f, 5.80f, 5.80f),
    "Soft" to
        listOf(5.50f, 2.50f, 0.90f, -2.60f, -5f, -10.60f, -12.40f, -13.30f, -10.60f, -6.70f),
    "Techno" to listOf(5.80f, 5.80f, 2.80f, -2.50f, -1.70f, -0.20f, 2.50f, 6.60f, 7f, 7.75f),
).mapValues { (_, gains) -> gains.map { it.coerceIn(-15f, 15f) } }

fun presetNameFor(gains: List<Float>): String =
    EQ_PRESETS.entries.firstOrNull { (_, preset) ->
        preset.size == gains.size && preset.indices.all { abs(preset[it] - gains[it]) < 0.01f }
    }?.key ?: "Custom"
