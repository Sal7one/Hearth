package com.sal7one.hearth.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Hearth's existing Ember palette, without Android context or resources. */
@Composable
internal fun HearthDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFF2B394), onPrimary = Color(0xFF482513),
        primaryContainer = Color(0xFF69402C), onPrimaryContainer = Color(0xFFFFE3D4),
        secondary = Color(0xFFCAD2A0), onSecondary = Color(0xFF30391D),
        background = Color(0xFF191714), onBackground = Color(0xFFF6EFE7),
        surface = Color(0xFF24211D), onSurface = Color(0xFFF6EFE7),
        surfaceVariant = Color(0xFF38322C), onSurfaceVariant = Color(0xFFCFC3B6),
        outline = Color(0xFF786C60), outlineVariant = Color(0xFF4A4138),
        error = Color(0xFFFFB4AB), errorContainer = Color(0xFF66342E),
    ), content = content)
}
