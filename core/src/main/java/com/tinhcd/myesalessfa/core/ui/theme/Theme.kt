package com.tinhcd.myesalessfa.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A field-sales app is used outdoors, at arm's length, on cheap screens.
// Fixed brand colours rather than Material You: the palette has to stay legible
// in sunlight and identical on every device, which dynamic colour cannot promise.
private val BrandBlue = Color(0xFF00549F)
private val BrandBlueDark = Color(0xFF003C73)
private val BrandBlueLight = Color(0xFF4C7FC4)
private val Accent = Color(0xFFF57C00)
private val Danger = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3F5),
    onPrimaryContainer = BrandBlueDark,
    secondary = Accent,
    onSecondary = Color.White,
    error = Danger,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF00243F),
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = Color(0xFFD5E3F5),
    secondary = Accent,
    onSecondary = Color(0xFF3A1D00),
    error = Color(0xFFEF9A9A),
    background = Color(0xFF121417),
    surface = Color(0xFF1A1D21),
)

val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
)

@Composable
fun MyeSalesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
