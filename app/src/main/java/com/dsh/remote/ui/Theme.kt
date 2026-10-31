package com.dsh.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dsh.remote.R

enum class ThemeMode(val label: String) {
    Dark("深色"),
    Light("浅色"),
    Beige("米色"),
}

/**
 * Font choices. The bundled Inter is the default look; the rest are platform families so the user
 * gets real variety without shipping more font files (each weights the APK by ~200 KB).
 */
enum class FontChoice(val label: String) {
    Inter("Inter"),
    System("系统默认"),
    Serif("衬线"),
    Mono("等宽"),
    Cursive("手写"),
}

val InterFamily: FontFamily = FontFamily(Font(R.font.inter, FontWeight.Normal))
private val SystemFamily: FontFamily = FontFamily.SansSerif

fun familyOf(choice: FontChoice): FontFamily = when (choice) {
    FontChoice.Inter -> InterFamily
    FontChoice.System -> SystemFamily
    FontChoice.Serif -> FontFamily.Serif
    FontChoice.Mono -> FontFamily.Monospace
    FontChoice.Cursive -> FontFamily.Cursive
}

/** Text sizes offered in the settings dropdown (factor applied to every type ramp entry). */
val FONT_SCALES: List<Pair<Float, String>> = listOf(
    0.80f to "特小",
    0.90f to "小",
    1.00f to "标准",
    1.10f to "稍大",
    1.20f to "大",
    1.30f to "更大",
    1.45f to "特大",
    1.60f to "超大",
)

/** Sizes follow TRAE's rhythm, then scale by the user's chosen factor. */
private fun typographyFor(family: FontFamily, scale: Float): Typography {
    fun style(weight: FontWeight, size: Float, lineHeight: Float) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * scale).sp,
        lineHeight = (lineHeight * scale).sp,
    )
    return Typography(
        displaySmall = style(FontWeight.Bold, 26f, 32f),
        headlineMedium = style(FontWeight.Bold, 22f, 28f),
        titleLarge = style(FontWeight.SemiBold, 18f, 24f),
        titleMedium = style(FontWeight.SemiBold, 15f, 21f),
        titleSmall = style(FontWeight.SemiBold, 13f, 18f),
        bodyLarge = style(FontWeight.Normal, 15f, 23f),
        bodyMedium = style(FontWeight.Normal, 14f, 21f),
        bodySmall = style(FontWeight.Normal, 12f, 17f),
        labelLarge = style(FontWeight.SemiBold, 14f, 19f),
        labelMedium = style(FontWeight.Medium, 12f, 16f),
        labelSmall = style(FontWeight.Medium, 11f, 15f),
    )
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF0A1020),
    primaryContainer = Color(0xFF25365E),
    onPrimaryContainer = Color(0xFFE2E8FF),
    secondary = Color(0xFF9FB2FF),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8ECF5),
    surface = Color(0xFF121B2C),
    onSurface = Color(0xFFE8ECF5),
    surfaceVariant = Color(0xFF1B2637),
    onSurfaceVariant = Color(0xFFA6B2C6),
    outline = Color(0xFF33425C),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF2B0A08),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFD9D5),
)

/** Matches TRAE's light canvas: pure white, hairline grey dividers, violet accent. */
private val TraeLight = lightColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEAFE),
    onPrimaryContainer = Color(0xFF241A6B),
    secondary = Color(0xFF6C5CE7),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF17181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17181C),
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = Color(0xFF8A8F98),
    outline = Color(0xFFEDEEF2),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF7A1B1B),
)

/** Warm paper, low contrast — easier at night. */
private val BeigeScheme = lightColorScheme(
    primary = Color(0xFF8A6A43),
    onPrimary = Color(0xFFFFFBF3),
    primaryContainer = Color(0xFFEFE2CC),
    onPrimaryContainer = Color(0xFF3B2C18),
    secondary = Color(0xFF6F6047),
    onSecondary = Color(0xFFFFFBF3),
    background = Color(0xFFF7F2E7),
    onBackground = Color(0xFF2B2519),
    surface = Color(0xFFFDF9F0),
    onSurface = Color(0xFF2B2519),
    surfaceVariant = Color(0xFFF0E9DA),
    onSurfaceVariant = Color(0xFF7A6C55),
    outline = Color(0xFFE0D5BF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFF8F2),
    errorContainer = Color(0xFFF6DDD6),
    onErrorContainer = Color(0xFF5C120C),
)

@Composable
fun DshTheme(
    mode: ThemeMode,
    fontChoice: FontChoice,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val scheme = when (mode) {
        ThemeMode.Dark -> DarkScheme
        ThemeMode.Light -> TraeLight
        ThemeMode.Beige -> BeigeScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typographyFor(familyOf(fontChoice), fontScale),
        content = content,
    )
}
