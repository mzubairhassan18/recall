package com.recall.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recall.app.R

private val Ink950 = Color(0xFF070A12)
private val Ink900 = Color(0xFF0A0F1C)
private val Ink800 = Color(0xFF111827)
private val Ink700 = Color(0xFF1A2336)
private val InkText = Color(0xFFF7F9FC)
private val InkMuted = Color(0xFFA8B3C7)
private val InkFaint = Color(0xFF6F7C93)
private val InkViolet = Color(0xFF7C6CFF)
private val InkCyan = Color(0xFF22D3EE)
private val InkMint = Color(0xFF34D399)
private val InkPink = Color(0xFFF472B6)
private val InkDanger = Color(0xFFFB7185)
private val InkBorder = Color(0x1AFFFFFF)

private val LightBackground = Color(0xFFF4F6FB)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE8ECF4)
private val LightText = Color(0xFF111827)
private val LightMuted = Color(0xFF5B6475)
private val LightFaint = Color(0xFF6B7280)
private val LightViolet = Color(0xFF5B4BE0)
private val LightCyan = Color(0xFF0E7490)
private val LightMint = Color(0xFF047857)
private val LightPink = Color(0xFFBE185D)
private val LightDanger = Color(0xFFB91C1C)
private val LightBorder = Color(0x1A111827)

val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
)

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_600, FontWeight.SemiBold),
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

private val RecallDarkColors = darkColorScheme(
    primary = InkViolet,
    onPrimary = Ink950,
    primaryContainer = Color(0xFF2A2454),
    onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = InkCyan,
    onSecondary = Ink950,
    secondaryContainer = Color(0xFF103B45),
    onSecondaryContainer = Color(0xFFD4FBFF),
    tertiary = InkMint,
    onTertiary = Ink950,
    tertiaryContainer = Color(0xFF0D3B2B),
    onTertiaryContainer = Color(0xFFC9F9E1),
    background = Ink950,
    onBackground = InkText,
    surface = Ink800,
    onSurface = InkText,
    surfaceVariant = Ink700,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFF33405A),
    outlineVariant = InkBorder,
    error = InkDanger,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4C151B),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val RecallLightColors = lightColorScheme(
    primary = LightViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF231A6B),
    secondary = LightCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F7FB),
    onSecondaryContainer = Color(0xFF063B45),
    tertiary = LightMint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightMuted,
    outline = LightFaint,
    outlineVariant = LightBorder,
    error = LightDanger,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val RecallTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.2).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val RecallShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

val LocalRecallDarkTheme = staticCompositionLocalOf { true }

val RecallText: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

val RecallMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val RecallFaint: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outline

val RecallViolet: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val RecallCyan: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val RecallMint: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.tertiary

val RecallPink: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalRecallDarkTheme.current) InkPink else LightPink

val RecallDanger: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.error

val RecallOnAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onPrimary

val RecallTagText: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onPrimaryContainer

val RecallSuccessText: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalRecallDarkTheme.current) Color(0xFFB7F7D5) else Color(0xFF047857)

val RecallGridLine: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground.copy(
        alpha = if (LocalRecallDarkTheme.current) 0.028f else 0.05f,
    )

val RecallBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant

val RecallFill: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)

val RecallBorderStrong: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

val RecallInk950: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val RecallInk900: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val RecallInk800: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val RecallInk700: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

@Composable
fun RecallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRecallDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) RecallDarkColors else RecallLightColors,
            typography = RecallTypography,
            shapes = RecallShapes,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
