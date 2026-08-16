package com.tinhcd.myesalessfa.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
    // Spelled out rather than left to Material's default, which is tinted from a
    // seed colour this scheme does not have. An error a rep has to read in
    // sunlight is not a good place to accept whatever tone falls out.
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFF8C1D1D),
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
    errorContainer = Color(0xFF4E1414),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121417),
    surface = Color(0xFF1A1D21),
)

val AppTypography = Typography(
    /** Reserved for the one thing a screen is about — currently the sign-in hero. */
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
    ),
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

/**
 * The brand band, which deliberately does not invert with the theme.
 *
 * `primary`/`onPrimary` cannot serve here. In the dark scheme those are a light
 * blue with near-black text on top, and a heading rendered that way is muddy on a
 * phone held at arm's length outdoors — observed on a real device. A brand header
 * is an identity rather than a surface: it stays deep blue with white on it in both
 * themes, and only the depth changes.
 */
@Immutable
data class BrandColors(
    val header: Color,
    val onHeader: Color,
)

private val LightBrand = BrandColors(header = BrandBlue, onHeader = Color.White)
private val DarkBrand = BrandColors(header = BrandBlueDark, onHeader = Color(0xFFEAF1FA))

val LocalBrandColors = staticCompositionLocalOf { LightBrand }

/** `MaterialTheme.brand` reads alongside `MaterialTheme.colorScheme` at call sites. */
val MaterialTheme.brand: BrandColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBrandColors.current

@Composable
fun MyeSalesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
