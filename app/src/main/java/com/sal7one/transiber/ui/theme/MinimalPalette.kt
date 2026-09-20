package com.sal7one.transiber.ui.theme

import androidx.compose.ui.graphics.Color

/** Neutral surfaces keep long text and controls readable in every workflow. */
internal fun minimalPalette(preset: AccentPreset): AccentPalette {
    val ink = preset == AccentPreset.INK
    val sky = preset == AccentPreset.SKY
    return AccentPalette(
        primary = Color(if (ink) 0xFF303036 else 0xFF275BA8), onPrimary = Color.White,
        primaryContainer = Color(if (ink) 0xFFE3E3E8 else 0xFFDCE8FF), onPrimaryContainer = Color(if (ink) 0xFF202024 else 0xFF163868),
        secondary = Color(0xFF536176), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE1E7F0), onSecondaryContainer = Color(0xFF253348),
        tertiary = Color(0xFF536176), onTertiary = Color.White,
        lightBackground = Color(if (sky) 0xFFF1F6FF else 0xFFF8F9FB), lightSurface = Color.White,
        lightSurfaceVariant = Color(if (sky) 0xFFDEE9F8 else 0xFFE9EBF0), lightOnGround = Color(0xFF191C22),
        lightOnSurfaceVariant = Color(0xFF505866), lightOutline = Color(0xFF757E8C),
        darkBackground = Color(if (ink) 0xFF000000 else if (sky) 0xFF0D1726 else 0xFF101216),
        darkSurface = Color(if (ink) 0xFF101012 else if (sky) 0xFF152236 else 0xFF191C22),
        darkSurfaceVariant = Color(if (sky) 0xFF263750 else 0xFF2D323C), darkOnGround = Color(0xFFF1F3F7),
        darkOnSurfaceVariant = Color(0xFFC3CAD6), darkOutline = Color(0xFF8F98A8),
        success = Color(0xFF286447), successContainer = Color(0xFFC8EED8), onSuccessContainer = Color(0xFF123924),
        warning = Color(0xFF825516), warningContainer = Color(0xFFFFE3B5), onWarningContainer = Color(0xFF492D04),
        info = Color(0xFF275BA8), infoContainer = Color(0xFFDCE8FF), onInfoContainer = Color(0xFF163868),
        error = Color(0xFFAD3040), errorContainer = Color(0xFFFFDADF), onErrorContainer = Color(0xFF660C21),
    )
}
