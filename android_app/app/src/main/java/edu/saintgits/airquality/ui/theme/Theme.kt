package edu.saintgits.airquality.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import edu.saintgits.airquality.ai.AirStatus
import edu.saintgits.airquality.ai.AqiCategory

private val Teal = Color(0xFF0E6B5C)
private val TealLight = Color(0xFF4FD1B5)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFF9FF2DC),
    secondary = Color(0xFF9CC9FF),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF1C242C),
    onSurfaceVariant = Color(0xFFB4BEC6),
    surfaceContainer = Color(0xFF171E25),
    surfaceContainerHigh = Color(0xFF1E272F),
    outline = Color(0xFF5F6B75),
    outlineVariant = Color(0xFF2C3740),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F0E0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF2F6198),
    background = Color(0xFFF6F8F9),
    onBackground = Color(0xFF181C1F),
    surface = Color(0xFFF6F8F9),
    onSurface = Color(0xFF181C1F),
    surfaceVariant = Color(0xFFE4E9EC),
    onSurfaceVariant = Color(0xFF424A50),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F4),
    outline = Color(0xFF727A80),
    outlineVariant = Color(0xFFD3DADE),
)

private val AppTypography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.copy(fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun AirQualityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** CPCB NAQI colour scale. */
fun AqiCategory.color(): Color = when (this) {
    AqiCategory.GOOD -> Color(0xFF00A65A)
    AqiCategory.SATISFACTORY -> Color(0xFF8CC63F)
    AqiCategory.MODERATE -> Color(0xFFE8C300)
    AqiCategory.POOR -> Color(0xFFF28C1F)
    AqiCategory.VERY_POOR -> Color(0xFFE53935)
    AqiCategory.SEVERE -> Color(0xFF9E1B1B)
}

fun AirStatus.color(): Color = when (this) {
    AirStatus.GOOD -> Color(0xFF00A65A)
    AirStatus.MODERATE -> Color(0xFFF2A900)
    AirStatus.POOR -> Color(0xFFE53935)
}

/** Level 0 (fine) .. 3 (bad) to a status colour. */
fun levelColor(level: Int): Color = when (level) {
    0 -> Color(0xFF00A65A)
    1 -> Color(0xFF8CC63F)
    2 -> Color(0xFFF2A900)
    else -> Color(0xFFE53935)
}

/** Distinct series colours that read in both themes. */
object SeriesColors {
    val blue = Color(0xFF4C8DF6)
    val orange = Color(0xFFF08A24)
    val teal = Color(0xFF1FB89A)
    val purple = Color(0xFF9B6CF0)
    val red = Color(0xFFE5534B)
    val gray = Color(0xFF8A96A0)
}
