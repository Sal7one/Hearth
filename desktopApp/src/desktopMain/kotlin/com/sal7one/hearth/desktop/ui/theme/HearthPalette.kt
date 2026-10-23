package com.sal7one.hearth.desktop.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The desktop design system's colour contract.
 *
 * Values mirror the Android palettes in `app/src/main/java/com/sal7one/transiber/ui/theme`
 * so the two applications cannot drift apart: Ink is the default, and each preset
 * supplies its own ground, surface and accent while the semantic ramp derives from
 * them. Nothing in a screen may hardcode a colour.
 */
@Immutable
internal data class HearthColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val raised: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textDim: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val warm: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val info: Color,
    val isDark: Boolean,
) {
    val accentSoft: Color get() = accent.copy(alpha = if (isDark) 0.12f else 0.10f)
    val accentLine: Color get() = accent.copy(alpha = if (isDark) 0.34f else 0.30f)
    val warmSoft: Color get() = warm.copy(alpha = 0.12f)
    val okSoft: Color get() = ok.copy(alpha = 0.13f)
    val warnSoft: Color get() = warn.copy(alpha = 0.13f)
    val errSoft: Color get() = err.copy(alpha = 0.13f)
    val infoSoft: Color get() = info.copy(alpha = 0.13f)

    /** Row hover and pressed states stay a step below the raised surface. */
    val hover: Color get() = if (isDark) surface2 else surface2
    val scrim: Color get() = Color.Black.copy(alpha = if (isDark) 0.82f else 0.32f)
}

/** The seven palettes Hearth has shipped on Android since 0.19.0. */
internal enum class AccentPreset(val label: String, val description: String) {
    CLEAN("Clean", "Neutral surfaces, quiet grey"),
    INK("Ink", "Monochrome, true black"),
    SKY("Sky", "Cool blue and soft slate"),
    ORGANIC("Organic", "Parchment and terracotta"),
    EMBER("Ember", "Clay, peach and olive"),
    FOREST("Forest", "Moss, bark and lichen"),
    AMETHYST("Amethyst", "Plum, rose and mineral grey"),
}

private fun Color.soften(factor: Float, dark: Boolean): Color {
    val target = if (dark) Color.Black else Color.White
    return Color(
        red = red + (target.red - red) * factor,
        green = green + (target.green - green) * factor,
        blue = blue + (target.blue - blue) * factor,
        alpha = 1f,
    )
}

private fun accentPalette(preset: AccentPreset, dark: Boolean): Triple<Color, Color, Color> {
    // ground, surface, accent
    return when (preset) {
        AccentPreset.INK -> if (dark)
            Triple(Color(0xFF000000), Color(0xFF101012), Color(0xFF3BE1EC))
        else Triple(Color(0xFFF8F9FB), Color(0xFFFFFFFF), Color(0xFF0D8F99))

        AccentPreset.CLEAN -> if (dark)
            Triple(Color(0xFF101216), Color(0xFF191C22), Color(0xFFB9C6DA))
        else Triple(Color(0xFFF8F9FB), Color(0xFFFFFFFF), Color(0xFF303036))

        AccentPreset.SKY -> if (dark)
            Triple(Color(0xFF0D1726), Color(0xFF152236), Color(0xFF67A6FF))
        else Triple(Color(0xFFF1F6FF), Color(0xFFFFFFFF), Color(0xFF275BA8))

        AccentPreset.ORGANIC -> if (dark)
            Triple(Color(0xFF201E1D), Color(0xFF2E2B25), Color(0xFFF2A576))
        else Triple(Color(0xFFF5EAD8), Color(0xFFF9F4ED), Color(0xFF934719))

        AccentPreset.EMBER -> if (dark)
            Triple(Color(0xFF241A16), Color(0xFF33251F), Color(0xFFF0A074))
        else Triple(Color(0xFFF7ECE3), Color(0xFFFFF8F3), Color(0xFFB65A2E))

        AccentPreset.FOREST -> if (dark)
            Triple(Color(0xFF1A2018), Color(0xFF262D23), Color(0xFFAFC98F))
        else Triple(Color(0xFFF0EEDF), Color(0xFFF9F7EA), Color(0xFF5D6D45))

        AccentPreset.AMETHYST -> if (dark)
            Triple(Color(0xFF211B20), Color(0xFF30272E), Color(0xFFD3A9C8))
        else Triple(Color(0xFFF3EDEF), Color(0xFFFCF7F9), Color(0xFF80627B))
    }
}

internal fun hearthColors(preset: AccentPreset, dark: Boolean): HearthColors {
    val (ground, surface, accent) = accentPalette(preset, dark)
    val surface2 = surface.soften(if (dark) -0.12f else 0.04f, dark)
    val surface3 = surface.soften(if (dark) -0.22f else 0.07f, dark)
    val raised = surface.soften(if (dark) -0.16f else 0.09f, dark)
    val border = if (dark) surface3 else Color(0xFFE3E5EA)
    val borderStrong = if (dark) surface3.soften(0.12f, dark) else Color(0xFFD2D6DE)

    val text = if (dark) Color(0xFFF1F3F7) else Color(0xFF191C22)
    val textDim = if (dark) Color(0xFFC3CAD6) else Color(0xFF505866)
    val muted = if (dark) Color(0xFF8F98A8) else Color(0xFF757E8C)
    val faint = if (dark) Color(0xFF5C6470) else Color(0xFFA2ABB8)

    val onAccent = if (dark) Color(0xFF04252A).let { if (preset == AccentPreset.INK) it else Color(0xFF10120F) }
    else Color.White

    return HearthColors(
        bg = ground,
        surface = surface,
        surface2 = surface2,
        surface3 = surface3,
        raised = raised,
        border = border,
        borderStrong = borderStrong,
        text = text,
        textDim = textDim,
        muted = muted,
        faint = faint,
        accent = accent,
        onAccent = onAccent,
        warm = if (dark) Color(0xFFF5B995) else Color(0xFFB65A2E),
        ok = if (dark) Color(0xFF6FD39A) else Color(0xFF286447),
        warn = if (dark) Color(0xFFF0C069) else Color(0xFF825516),
        err = if (dark) Color(0xFFFF8B7E) else Color(0xFFAD3040),
        info = if (dark) Color(0xFF8BB8FF) else Color(0xFF275BA8),
        isDark = dark,
    )
}
