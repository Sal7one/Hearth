package com.sal7one.hearth.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalHearthColors = staticCompositionLocalOf { hearthColors(AccentPreset.INK, true) }
internal val LocalHearthShapes = staticCompositionLocalOf { HearthShapes() }

/** True when the operator asked the system to stop decorative motion. */
internal val LocalReduceMotion = staticCompositionLocalOf { false }

/** Access point for the design tokens inside composables. */
internal object HearthTheme {
    val colors: HearthColors
        @Composable get() = LocalHearthColors.current

    val shapes: HearthShapes
        @Composable get() = LocalHearthShapes.current
}

/**
 * Applies the palette, type scale and shapes. Material3 is still the underlying
 * theme so stock controls (menus, dialogs, text fields) inherit sane colours, but
 * every Hearth component reads [HearthTheme.colors] directly.
 */
@Composable
internal fun HearthTheme(
    preset: AccentPreset = AccentPreset.INK,
    dark: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(preset, dark) { hearthColors(preset, dark) }
    val shapes = remember { HearthShapes() }
    val scheme = remember(colors) {
        if (colors.isDark) darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.text,
            secondary = colors.warm,
            onSecondary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.textDim,
            outline = colors.muted,
            outlineVariant = colors.border,
            error = colors.err,
            onError = colors.bg,
            errorContainer = colors.errSoft,
            onErrorContainer = colors.text,
        ) else lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.text,
            secondary = colors.warm,
            onSecondary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.textDim,
            outline = colors.muted,
            outlineVariant = colors.border,
            error = colors.err,
            onError = Color.White,
            errorContainer = colors.errSoft,
            onErrorContainer = colors.text,
        )
    }

    val typography = remember {
        Typography(
            displayLarge = HearthType.display,
            headlineLarge = HearthType.h1,
            headlineMedium = HearthType.h1,
            headlineSmall = HearthType.h2,
            titleLarge = HearthType.h2,
            titleMedium = HearthType.h3,
            titleSmall = HearthType.label,
            bodyLarge = HearthType.body,
            bodyMedium = HearthType.body,
            bodySmall = HearthType.small,
            labelLarge = HearthType.label,
            labelMedium = HearthType.small,
            labelSmall = HearthType.micro,
        )
    }

    CompositionLocalProvider(
        LocalHearthColors provides colors,
        LocalHearthShapes provides shapes,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
