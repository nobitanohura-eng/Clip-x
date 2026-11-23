package com.clipai.v2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StudioColors {
    val ObsidianCanvas = Color(0xFF0B0D12)
    val SurfaceCard = Color(0xFF131720)
    val SurfaceElevated = Color(0xFF1B202C)
    val HairlineBorder = Color(0xFF262C3A)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val AccentAmber = Color(0xFFF59E0B)
    val KaraokeYellow = Color(0xFFFACC15)
    val OnAmber = Color(0xFF0B0D12)
    val SuccessEmerald = Color(0xFF10B981)
    val WarningAmber = Color(0xFFF59E0B)
    val ErrorCrimson = Color(0xFFEF4444)
}

private val ClipAiDarkScheme = darkColorScheme(
    primary = StudioColors.AccentAmber,
    onPrimary = StudioColors.OnAmber,
    secondary = StudioColors.SuccessEmerald,
    onSecondary = Color(0xFF052E16),
    background = StudioColors.ObsidianCanvas,
    onBackground = StudioColors.TextPrimary,
    surface = StudioColors.SurfaceCard,
    onSurface = StudioColors.TextPrimary,
    surfaceVariant = StudioColors.SurfaceElevated,
    onSurfaceVariant = StudioColors.TextSecondary,
    outline = StudioColors.HairlineBorder,
    error = StudioColors.ErrorCrimson,
    onError = Color.White
)

private val ClipAiTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun ClipAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClipAiDarkScheme,
        typography = ClipAiTypography,
        content = content
    )
}
