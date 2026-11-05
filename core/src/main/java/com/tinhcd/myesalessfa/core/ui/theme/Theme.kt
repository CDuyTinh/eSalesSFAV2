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

/**
 * What a status means, as a colour: done, waiting, wrong, informational, the
 * odd one out, and switched off. Screens used to pick their own hex for each,
 * which drifted (three greens, four reds) and, being tuned for white paper, fell
 * to near-invisible on the dark surface. One set, one tone per theme.
 *
 * The strong tones are for text, icons, bars and the dots on a map. A filled
 * badge puts [onStatus] on top of one. The containers are the pale chip
 * backgrounds, paired with their own ink.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val accent: Color,
    val neutral: Color,
    val onStatus: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF04A489),
    warning = Color(0xFFF5A202),
    danger = Color(0xFFD5262B),
    info = Color(0xFF2D2DFE),
    accent = Color(0xFF5C00D4),
    neutral = Color(0xFF8A8A93),
    onStatus = Color.White,
    successContainer = Color(0xFFE6F4EA),
    onSuccessContainer = Color(0xFF1E7B34),
    warningContainer = Color(0xFFFFF3E0),
    onWarningContainer = Color(0xFFB45309),
    infoContainer = Color(0xFFE3F2FD),
    onInfoContainer = Color(0xFF0B57A4),
)

// Lifted until each clears 4.5:1 on the dark surface; badges then carry dark
// ink, since white on a light tone is the same contrast problem reversed.
private val DarkStatus = StatusColors(
    success = Color(0xFF3CC9A8),
    warning = Color(0xFFFFB74D),
    danger = Color(0xFFFF7A7A),
    info = Color(0xFF9AA5FF),
    accent = Color(0xFFBB93FF),
    neutral = Color(0xFFA6A6B0),
    onStatus = Color(0xFF111316),
    successContainer = Color(0xFF173A2C),
    onSuccessContainer = Color(0xFF9EE6C4),
    warningContainer = Color(0xFF45300F),
    onWarningContainer = Color(0xFFFFD49A),
    infoContainer = Color(0xFF16304D),
    onInfoContainer = Color(0xFFB5D4FF),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** `MaterialTheme.status.success`, beside `colorScheme` and `brand`. */
val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

@Composable
fun MyeSalesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand,
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
