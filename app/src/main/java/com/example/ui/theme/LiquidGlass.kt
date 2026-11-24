package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LiquidGlassTokens(
    val isDark: Boolean,
    val isAmoled: Boolean = false,
    val baseGradientTop: Color,
    val baseGradientBottom: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val orbAccent: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val glassSurfaceTinted: Color,
    val specularBorderTop: Color,
    val specularBorderMid: Color,
    val specularBorderBottom: Color,
    val innerSheenTop: Color,
    val innerSheenBottom: Color
)

val DarkLiquidGlassTokens = LiquidGlassTokens(
    isDark = true,
    isAmoled = false,
    baseGradientTop = Color(0xFF070B14),
    baseGradientBottom = Color(0xFF0B1220),
    orbPrimary = Color(0xFF14B8A6),
    orbSecondary = Color(0xFF38BDF8),
    orbAccent = Color(0xFFF59E0B),
    glassSurface = Color(0xCC131B2A),
    glassSurfaceElevated = Color(0xDD1A2538),
    glassSurfaceTinted = Color(0xA6113B3D),
    specularBorderTop = Color(0x52FFFFFF),
    specularBorderMid = Color(0x1E94D8F8),
    specularBorderBottom = Color(0x14FFFFFF),
    innerSheenTop = Color(0x18FFFFFF),
    innerSheenBottom = Color(0x00FFFFFF)
)

val AmoledLiquidGlassTokens = LiquidGlassTokens(
    isDark = true,
    isAmoled = true,
    baseGradientTop = Color(0xFF000000),
    baseGradientBottom = Color(0xFF000000),
    orbPrimary = Color(0xFF14B8A6),
    orbSecondary = Color(0xFF38BDF8),
    orbAccent = Color(0xFF818CF8),
    glassSurface = Color(0xE0080B10),
    glassSurfaceElevated = Color(0xEB0E131B),
    glassSurfaceTinted = Color(0xCC072426),
    specularBorderTop = Color(0x47FFFFFF),
    specularBorderMid = Color(0x1F5EEAD4),
    specularBorderBottom = Color(0x12FFFFFF),
    innerSheenTop = Color(0x12FFFFFF),
    innerSheenBottom = Color(0x00FFFFFF)
)

val LightLiquidGlassTokens = LiquidGlassTokens(
    isDark = false,
    isAmoled = false,
    baseGradientTop = Color(0xFFEDF3FA),
    baseGradientBottom = Color(0xFFF6F2EC),
    orbPrimary = Color(0xFF2DD4BF),
    orbSecondary = Color(0xFF60A5FA),
    orbAccent = Color(0xFFFBBF24),
    glassSurface = Color(0xC8FFFFFF),
    glassSurfaceElevated = Color(0xE0FFFFFF),
    glassSurfaceTinted = Color(0xB8DDF7F4),
    specularBorderTop = Color(0xEEFFFFFF),
    specularBorderMid = Color(0x80B7D0EC),
    specularBorderBottom = Color(0x557B96B8),
    innerSheenTop = Color(0x66FFFFFF),
    innerSheenBottom = Color(0x00FFFFFF)
)

val LocalLiquidGlass = staticCompositionLocalOf { DarkLiquidGlassTokens }

@Composable
fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalLiquidGlass.current
    val orbAlphaPrimary = when {
        glass.isAmoled -> 0.08f
        glass.isDark -> 0.22f
        else -> 0.26f
    }
    val orbAlphaSecondary = when {
        glass.isAmoled -> 0.06f
        glass.isDark -> 0.18f
        else -> 0.24f
    }
    val orbAlphaAccent = when {
        glass.isAmoled -> 0.04f
        glass.isDark -> 0.11f
        else -> 0.16f
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // Cache all 4 GPU gradient shaders once per layout size / theme change
                // instead of reallocating RadialGradient shaders on every frame redraw.
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val baseBrush = Brush.verticalGradient(
                        colors = listOf(glass.baseGradientTop, glass.baseGradientBottom)
                    )
                    val c1 = Offset(w * 0.14f, h * 0.12f)
                    val r1 = (w * 0.85f).coerceAtLeast(1f)
                    val b1 = Brush.radialGradient(
                        colors = listOf(glass.orbPrimary.copy(alpha = orbAlphaPrimary), Color.Transparent),
                        center = c1,
                        radius = r1
                    )
                    val c2 = Offset(w * 0.92f, h * 0.44f)
                    val r2 = (w * 0.78f).coerceAtLeast(1f)
                    val b2 = Brush.radialGradient(
                        colors = listOf(glass.orbSecondary.copy(alpha = orbAlphaSecondary), Color.Transparent),
                        center = c2,
                        radius = r2
                    )
                    val c3 = Offset(w * 0.22f, h * 0.92f)
                    val r3 = (w * 0.72f).coerceAtLeast(1f)
                    val b3 = Brush.radialGradient(
                        colors = listOf(glass.orbAccent.copy(alpha = orbAlphaAccent), Color.Transparent),
                        center = c3,
                        radius = r3
                    )

                    onDrawBehind {
                        drawRect(brush = baseBrush)
                        drawCircle(brush = b1, radius = r1, center = c1)
                        drawCircle(brush = b2, radius = r2, center = c2)
                        drawCircle(brush = b3, radius = r3, center = c3)
                    }
                },
            content = content
        )
    }
}

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    elevated: Boolean = false,
    tinted: Boolean = false,
    borderWidth: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val fillColor = when {
        tinted -> glass.glassSurfaceTinted
        elevated -> glass.glassSurfaceElevated
        else -> glass.glassSurface
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    // Memoize border and sheen brushes per theme token to avoid per-recomposition allocations
    val borderStroke = remember(glass, borderWidth) {
        BorderStroke(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = listOf(
                    glass.specularBorderTop,
                    glass.specularBorderMid,
                    glass.specularBorderBottom
                )
            )
        )
    }
    val sheenBrush = remember(glass) {
        Brush.verticalGradient(
            colors = listOf(
                glass.innerSheenTop,
                glass.innerSheenBottom
            )
        )
    }
    val shadow = if (elevated && !glass.isAmoled) 4.dp else 0.dp

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = fillColor,
            contentColor = contentColor,
            border = borderStroke,
            shadowElevation = shadow,
            modifier = modifier
        ) {
            Box(modifier = Modifier.background(sheenBrush)) {
                content()
            }
        }
    } else {
        Surface(
            shape = shape,
            color = fillColor,
            contentColor = contentColor,
            border = borderStroke,
            shadowElevation = shadow,
            modifier = modifier
        ) {
            Box(modifier = Modifier.background(sheenBrush)) {
                content()
            }
        }
    }
}

@Composable
fun Modifier.liquidGlassModifier(
    shape: Shape = RoundedCornerShape(22.dp),
    elevated: Boolean = false,
    tinted: Boolean = false
): Modifier {
    val glass = LocalLiquidGlass.current
    val fillColor = when {
        tinted -> glass.glassSurfaceTinted
        elevated -> glass.glassSurfaceElevated
        else -> glass.glassSurface
    }
    val sheenBrush = remember(glass) {
        Brush.verticalGradient(
            colors = listOf(glass.innerSheenTop, glass.innerSheenBottom)
        )
    }
    val borderBrush = remember(glass) {
        Brush.linearGradient(
            colors = listOf(
                glass.specularBorderTop,
                glass.specularBorderMid,
                glass.specularBorderBottom
            )
        )
    }
    return this
        .clip(shape)
        .background(fillColor)
        .background(sheenBrush)
        .border(
            width = 1.dp,
            brush = borderBrush,
            shape = shape
        )
}
