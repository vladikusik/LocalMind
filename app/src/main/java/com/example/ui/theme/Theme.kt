package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.data.preferences.ThemeMode

private val StudioLightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = EmeraldOnPrimaryLight,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = EmeraldOnContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = SlateOnContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    background = AlabasterBackground,
    onBackground = StudioInk,
    surface = AlabasterSurface,
    onSurface = StudioInk,
    surfaceVariant = AlabasterSurfaceContainer,
    onSurfaceVariant = StudioInkMuted,
    surfaceContainerLowest = AlabasterSurfaceLowest,
    surfaceContainerLow = AlabasterSurfaceLow,
    surfaceContainer = AlabasterSurfaceContainer,
    surfaceContainerHigh = AlabasterSurfaceHigh,
    surfaceContainerHighest = AlabasterSurfaceHighest,
    outline = StudioOutline,
    outlineVariant = StudioOutlineVariant,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = MintPrimaryDark,
    onPrimary = MintOnPrimaryDark,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = MintOnContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = SlateOnContainerDark,
    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    background = ObsidianBackground,
    onBackground = ObsidianInk,
    surface = ObsidianSurface,
    onSurface = ObsidianInk,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = ObsidianInkMuted,
    surfaceContainerLowest = ObsidianSurfaceLowest,
    surfaceContainerLow = ObsidianSurfaceLow,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

private val StudioAmoledColorScheme = darkColorScheme(
    primary = Color(0xFF2EF2C9),
    onPrimary = Color(0xFF00201A),
    primaryContainer = Color(0xFF063830),
    onPrimaryContainer = Color(0xFF9DF7E5),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0A1019),
    secondaryContainer = Color(0xFF141B24),
    onSecondaryContainer = Color(0xFFD6E0EE),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF241600),
    tertiaryContainer = Color(0xFF332200),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F7FB),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF4F7FB),
    surfaceVariant = Color(0xFF0B0E14),
    onSurfaceVariant = Color(0xFFA0AEC0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF070A0F),
    surfaceContainer = Color(0xFF0C1017),
    surfaceContainerHigh = Color(0xFF121822),
    surfaceContainerHighest = Color(0xFF19212E),
    outline = Color(0xFF344154),
    outlineVariant = Color(0xFF1E2633),
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = Color(0xFF471111),
    onErrorContainer = Color(0xFFFEE2E2)
)

@Immutable
data class StudioExtendedColors(
    val codeBackground: Color,
    val codeHeader: Color,
    val codeText: Color,
    val inlineCodeBackground: Color,
    val statusOnline: Color,
    val statusWarning: Color,
    val statusOffline: Color,
    val userBubbleBackground: Color,
    val userBubbleBorder: Color
)

private val LightExtendedColors = StudioExtendedColors(
    codeBackground = CodeBgLight,
    codeHeader = CodeHeaderLight,
    codeText = CodeTextLight,
    inlineCodeBackground = InlineCodeBgLight,
    statusOnline = StatusOnlineLight,
    statusWarning = StatusWarnLight,
    statusOffline = StatusOfflineLight,
    userBubbleBackground = AlabasterSurfaceLowest,
    userBubbleBorder = StudioOutlineVariant
)

private val DarkExtendedColors = StudioExtendedColors(
    codeBackground = CodeBgDark,
    codeHeader = CodeHeaderDark,
    codeText = CodeTextDark,
    inlineCodeBackground = InlineCodeBgDark,
    statusOnline = StatusOnlineDark,
    statusWarning = StatusWarnDark,
    statusOffline = StatusOfflineDark,
    userBubbleBackground = ObsidianSurfaceContainer,
    userBubbleBorder = ObsidianOutlineVariant
)

private val AmoledExtendedColors = StudioExtendedColors(
    codeBackground = Color(0xFF05070B),
    codeHeader = Color(0xFF0E131B),
    codeText = Color(0xFFF1F5F9),
    inlineCodeBackground = Color(0xFF141B24),
    statusOnline = Color(0xFF34D399),
    statusWarning = Color(0xFFFBBF24),
    statusOffline = Color(0xFFF87171),
    userBubbleBackground = Color(0xFF0A1417),
    userBubbleBorder = Color(0xFF1C2C33)
)

val LocalStudioColors = staticCompositionLocalOf { DarkExtendedColors }

@Composable
fun LocalMindTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isAmoled = themeMode == ThemeMode.AMOLED
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val colorScheme = when {
        isAmoled -> StudioAmoledColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> StudioDarkColorScheme
        else -> StudioLightColorScheme
    }

    val extendedColors = when {
        isAmoled -> AmoledExtendedColors
        darkTheme -> DarkExtendedColors
        else -> LightExtendedColors
    }
    val liquidGlassTokens = when {
        isAmoled -> AmoledLiquidGlassTokens
        darkTheme -> DarkLiquidGlassTokens
        else -> LightLiquidGlassTokens
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalStudioColors provides extendedColors,
        LocalLiquidGlass provides liquidGlassTokens
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
