package dev.lilt.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalLanguage = staticCompositionLocalOf { "en" }

@Composable
fun t(en: String, zh: String): String =
    if (LocalLanguage.current == "zh") zh else en

data class AccentChoice(val id: String, val name: String, val color: Color)

val accents = listOf(
    AccentChoice("ember", "Ember", Color(0xFFF2B46B)),
    AccentChoice("sage", "Sage", Color(0xFF9BC49A)),
    AccentChoice("sky", "Sky", Color(0xFF8FB7F0)),
    AccentChoice("rose", "Rose", Color(0xFFE6A0B4)),
    AccentChoice("violet", "Violet", Color(0xFFB4A4F4)),
)

fun accentColor(id: String): Color = accents.firstOrNull { it.id == id }?.color ?: accents[0].color

private fun scheme(theme: String, accent: Color) = when (theme) {
    "light" -> lightColorScheme(
        primary = accent,
        onPrimary = Color(0xFF201A12),
        secondary = accent,
        background = Color(0xFFF6F2EC),
        onBackground = Color(0xFF211E1A),
        surface = Color(0xFFFFFBF6),
        onSurface = Color(0xFF211E1A),
        surfaceVariant = Color(0xFFEDE6DC),
        onSurfaceVariant = Color(0xFF5E574E),
        outline = Color(0xFFCFC6B9),
    )
    "dark" -> darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF201A12),
        secondary = accent,
        background = Color(0xFF141318),
        onBackground = Color(0xFFF1EDE6),
        surface = Color(0xFF1D1B22),
        onSurface = Color(0xFFF1EDE6),
        surfaceVariant = Color(0xFF2A2730),
        onSurfaceVariant = Color(0xFFB8B1C4),
        outline = Color(0xFF3E3A48),
    )
    else -> darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF201A12),
        secondary = accent,
        background = Color(0xFF000000),
        onBackground = Color(0xFFF1EDE6),
        surface = Color(0xFF0C0C0F),
        onSurface = Color(0xFFF1EDE6),
        surfaceVariant = Color(0xFF18171D),
        onSurfaceVariant = Color(0xFFB8B1C4),
        outline = Color(0xFF2B2932),
    )
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun LiltTheme(theme: String, accentId: String, language: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    }
    val chosen = if (theme == "system") {
        if (dark) "dark" else "light"
    } else theme
    CompositionLocalProvider(LocalLanguage provides language) {
        MaterialTheme(
            colorScheme = scheme(chosen, accentColor(accentId)),
            typography = typography,
            content = content,
        )
    }
}
