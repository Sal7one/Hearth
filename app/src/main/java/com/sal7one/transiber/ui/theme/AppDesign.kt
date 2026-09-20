package com.sal7one.transiber.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Layout and type tokens from the committed Organic design system. */
object AppDesign {
    object Dimens {
        val SpacingXs = 4.dp
        val SpacingSm = 9.dp
        val SpacingMd = 18.dp
        val SpacingLg = 26.dp
        val SpacingXl = 35.dp
        val SpacingXxl = 52.dp

        val RadiusSm = 12.dp
        val RadiusMd = 20.dp
        val RadiusLg = 28.dp
        val RadiusXl = 32.dp
        val RadiusFull = 100.dp

        val IconSm = 16.dp
        val IconMd = 24.dp
        val IconLg = 32.dp
        val IconXl = 48.dp
        val IconXxl = 64.dp

        val ButtonHeight = 56.dp
        val ButtonHeightSm = 44.dp

        val ElevationNone = 0.dp
        val ElevationSm = 2.dp
        val ElevationMd = 4.dp
        val ElevationLg = 8.dp

        val BottomNavHeight = 72.dp
    }

    // Kept for existing call sites that use AppDesign.Type directly.
    object Type {
        val DisplayLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
        val DisplayMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
        val HeadlineLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
        val HeadlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        val TitleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
        val TitleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        val BodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp)
        val BodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
        val BodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
        val LabelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        val LabelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        val LabelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class AccentPreset(val label: String, val description: String) {
    CLEAN("Clean", "Neutral surfaces with a quiet blue accent"),
    INK("Ink", "Monochrome, with a black background in dark mode"),
    SKY("Sky", "Cool blue and soft slate"),
    // The enum name is retained for compatibility with preferences saved by older builds.
    OCEAN("Organic · classic", "The original parchment, terracotta and sage theme"),
    EMBER("Ember", "Clay, peach and olive"),
    FOREST("Forest", "Moss, bark and lichen"),
    AMETHYST("Amethyst", "Plum, rose and mineral grey");

    companion object {
        fun fromStored(value: String?): AccentPreset = entries.firstOrNull { it.name == value } ?: CLEAN
    }
}

/**
 * A complete palette, rather than an accent swatch. Every visible app color is
 * derived from this object so future palettes can change the ground, surfaces,
 * status colors and workflow identities together without editing a screen.
 */
@Immutable
data class AccentPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val lightBackground: Color,
    val lightSurface: Color,
    val lightSurfaceVariant: Color,
    val lightOnGround: Color,
    val lightOnSurfaceVariant: Color,
    val lightOutline: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceVariant: Color,
    val darkOnGround: Color,
    val darkOnSurfaceVariant: Color,
    val darkOutline: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
) {
    val previewColors: List<Color>
        get() = listOf(primary, secondary, tertiary)
}

fun accentPalette(preset: AccentPreset): AccentPalette = when (preset) {
    AccentPreset.CLEAN, AccentPreset.INK, AccentPreset.SKY -> minimalPalette(preset)

    AccentPreset.OCEAN -> AccentPalette(
        primary = Color(0xFF934719), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE1D0), onPrimaryContainer = Color(0xFF71370F),
        secondary = Color(0xFF68784D), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE1EECC), onSecondaryContainer = Color(0xFF33421F),
        tertiary = Color(0xFF9B5C2B), onTertiary = Color(0xFFFFFFFF),
        lightBackground = Color(0xFFF5EAD8), lightSurface = Color(0xFFF9F4ED),
        lightSurfaceVariant = Color(0xFFEBDDC5), lightOnGround = Color(0xFF201E1D),
        lightOnSurfaceVariant = Color(0xFF645C50), lightOutline = Color(0xFF82796A),
        darkBackground = Color(0xFF201E1D), darkSurface = Color(0xFF2E2B25),
        darkSurfaceVariant = Color(0xFF474238), darkOnGround = Color(0xFFF9F4ED),
        darkOnSurfaceVariant = Color(0xFFDCD3C4), darkOutline = Color(0xFF9A9182),
        success = Color(0xFF56633F), successContainer = Color(0xFFCCDBB2), onSuccessContainer = Color(0xFF283517),
        warning = Color(0xFF9B5C2B), warningContainer = Color(0xFFFFDCC3), onWarningContainer = Color(0xFF552500),
        info = Color(0xFF5F6F72), infoContainer = Color(0xFFD7E7EA), onInfoContainer = Color(0xFF24383B),
        error = Color(0xFF9C3A22), errorContainer = Color(0xFFF7DDD2), onErrorContainer = Color(0xFF6B2617),
    )
    AccentPreset.EMBER -> AccentPalette(
        primary = Color(0xFFB65A2E), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCC9), onPrimaryContainer = Color(0xFF70300F),
        secondary = Color(0xFF747A4A), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE5E7BC), onSecondaryContainer = Color(0xFF393F14),
        tertiary = Color(0xFF9A6544), onTertiary = Color(0xFFFFFFFF),
        lightBackground = Color(0xFFF7ECE3), lightSurface = Color(0xFFFFF8F3),
        lightSurfaceVariant = Color(0xFFF0DCCF), lightOnGround = Color(0xFF251B17),
        lightOnSurfaceVariant = Color(0xFF6B574D), lightOutline = Color(0xFF8C7468),
        darkBackground = Color(0xFF241A16), darkSurface = Color(0xFF33251F),
        darkSurfaceVariant = Color(0xFF4D3930), darkOnGround = Color(0xFFFFF2EA),
        darkOnSurfaceVariant = Color(0xFFE5CEC1), darkOutline = Color(0xFFA99083),
        success = Color(0xFF5C6841), successContainer = Color(0xFFDCE8B9), onSuccessContainer = Color(0xFF2C3715),
        warning = Color(0xFFA15B24), warningContainer = Color(0xFFFFDCC1), onWarningContainer = Color(0xFF572800),
        info = Color(0xFF646D78), infoContainer = Color(0xFFDDE5F2), onInfoContainer = Color(0xFF29333E),
        error = Color(0xFFA23C2D), errorContainer = Color(0xFFFFDAD2), onErrorContainer = Color(0xFF6F241A),
    )
    AccentPreset.FOREST -> AccentPalette(
        primary = Color(0xFF5D6D45), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE9BE), onPrimaryContainer = Color(0xFF2F3B19),
        secondary = Color(0xFF8A5C3D), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBC8), onSecondaryContainer = Color(0xFF502A14),
        tertiary = Color(0xFF4E716B), onTertiary = Color(0xFFFFFFFF),
        lightBackground = Color(0xFFF0EEDF), lightSurface = Color(0xFFF9F7EA),
        lightSurfaceVariant = Color(0xFFDFE3CD), lightOnGround = Color(0xFF1C2018),
        lightOnSurfaceVariant = Color(0xFF59604F), lightOutline = Color(0xFF747C69),
        darkBackground = Color(0xFF1A2018), darkSurface = Color(0xFF262D23),
        darkSurfaceVariant = Color(0xFF3A4435), darkOnGround = Color(0xFFF0F2E7),
        darkOnSurfaceVariant = Color(0xFFD1D8C8), darkOutline = Color(0xFF919B87),
        success = Color(0xFF4E6940), successContainer = Color(0xFFD0E9C0), onSuccessContainer = Color(0xFF24391A),
        warning = Color(0xFF956128), warningContainer = Color(0xFFFFDCB7), onWarningContainer = Color(0xFF512C00),
        info = Color(0xFF4E716B), infoContainer = Color(0xFFCDEBE5), onInfoContainer = Color(0xFF173D38),
        error = Color(0xFF984036), errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF672720),
    )
    AccentPreset.AMETHYST -> AccentPalette(
        primary = Color(0xFF80627B), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF1D9EC), onPrimaryContainer = Color(0xFF4C3048),
        secondary = Color(0xFF8D5B57), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDAD6), onSecondaryContainer = Color(0xFF552825),
        tertiary = Color(0xFF5E6D84), onTertiary = Color(0xFFFFFFFF),
        lightBackground = Color(0xFFF3EDEF), lightSurface = Color(0xFFFCF7F9),
        lightSurfaceVariant = Color(0xFFE9DFE5), lightOnGround = Color(0xFF221D21),
        lightOnSurfaceVariant = Color(0xFF655A63), lightOutline = Color(0xFF80727D),
        darkBackground = Color(0xFF211B20), darkSurface = Color(0xFF30272E),
        darkSurfaceVariant = Color(0xFF493C46), darkOnGround = Color(0xFFF8EEF5),
        darkOnSurfaceVariant = Color(0xFFDDD0D9), darkOutline = Color(0xFF9D8E99),
        success = Color(0xFF566B50), successContainer = Color(0xFFD6E8CF), onSuccessContainer = Color(0xFF283D24),
        warning = Color(0xFF95602E), warningContainer = Color(0xFFFFDCBE), onWarningContainer = Color(0xFF522B00),
        info = Color(0xFF5E6D84), infoContainer = Color(0xFFD9E2F7), onInfoContainer = Color(0xFF29384D),
        error = Color(0xFF9A3D4E), errorContainer = Color(0xFFFFD9DF), onErrorContainer = Color(0xFF682534),
    )
}

@Immutable
data class HearthSemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val ffmpeg: Color,
    val whisper: Color,
    val vosk: Color,
    val onnx: Color,
    val microphone: Color,
    val onMedia: Color,
    val mediaScrim: Color,
    val detectionPalette: List<Color>,
)

private val LocalHearthColors = staticCompositionLocalOf {
    HearthSemanticColors(
        success = Color.Unspecified, successContainer = Color.Unspecified, onSuccessContainer = Color.Unspecified,
        warning = Color.Unspecified, warningContainer = Color.Unspecified, onWarningContainer = Color.Unspecified,
        info = Color.Unspecified, infoContainer = Color.Unspecified, onInfoContainer = Color.Unspecified,
        ffmpeg = Color.Unspecified, whisper = Color.Unspecified, vosk = Color.Unspecified,
        onnx = Color.Unspecified, microphone = Color.Unspecified,
        onMedia = Color.White, mediaScrim = Color.Black,
        detectionPalette = emptyList(),
    )
}

object HearthTheme {
    val colors: HearthSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHearthColors.current
}

private fun lightScheme(palette: AccentPalette) = lightColorScheme(
    primary = palette.primary, onPrimary = palette.onPrimary,
    primaryContainer = palette.primaryContainer, onPrimaryContainer = palette.onPrimaryContainer,
    secondary = palette.secondary, onSecondary = palette.onSecondary,
    secondaryContainer = palette.secondaryContainer, onSecondaryContainer = palette.onSecondaryContainer,
    tertiary = palette.tertiary, onTertiary = palette.onTertiary,
    background = palette.lightBackground, onBackground = palette.lightOnGround,
    surface = palette.lightSurface, onSurface = palette.lightOnGround,
    surfaceTint = palette.primary,
    surfaceContainerLowest = palette.lightSurface,
    surfaceContainerLow = palette.lightSurface,
    surfaceContainer = palette.lightSurfaceVariant.copy(alpha = 0.35f).compositeOver(palette.lightSurface),
    surfaceContainerHigh = palette.lightSurfaceVariant.copy(alpha = 0.65f).compositeOver(palette.lightSurface),
    surfaceContainerHighest = palette.lightSurfaceVariant,
    inverseSurface = palette.darkSurface, inverseOnSurface = palette.darkOnGround,
    inversePrimary = palette.primaryContainer,
    surfaceVariant = palette.lightSurfaceVariant, onSurfaceVariant = palette.lightOnSurfaceVariant,
    error = palette.error, onError = Color.White,
    errorContainer = palette.errorContainer, onErrorContainer = palette.onErrorContainer,
    outline = palette.lightOutline, outlineVariant = palette.lightOutline.copy(alpha = 0.4f),
    scrim = Color.Black,
)

private fun darkScheme(palette: AccentPalette) = darkColorScheme(
    primary = palette.primaryContainer, onPrimary = palette.onPrimaryContainer,
    primaryContainer = palette.primary.copy(alpha = 0.52f).compositeOver(palette.darkSurface), onPrimaryContainer = palette.darkOnGround,
    secondary = palette.secondaryContainer, onSecondary = palette.onSecondaryContainer,
    secondaryContainer = palette.secondary.copy(alpha = 0.5f).compositeOver(palette.darkSurface), onSecondaryContainer = palette.darkOnGround,
    tertiary = palette.tertiary, onTertiary = palette.onTertiary,
    background = palette.darkBackground, onBackground = palette.darkOnGround,
    surface = palette.darkSurface, onSurface = palette.darkOnGround,
    surfaceTint = palette.primaryContainer,
    surfaceContainerLowest = palette.darkBackground,
    surfaceContainerLow = palette.darkSurface,
    surfaceContainer = palette.darkSurfaceVariant.copy(alpha = 0.35f).compositeOver(palette.darkSurface),
    surfaceContainerHigh = palette.darkSurfaceVariant.copy(alpha = 0.65f).compositeOver(palette.darkSurface),
    surfaceContainerHighest = palette.darkSurfaceVariant,
    inverseSurface = palette.lightSurface, inverseOnSurface = palette.lightOnGround,
    inversePrimary = palette.primary,
    surfaceVariant = palette.darkSurfaceVariant, onSurfaceVariant = palette.darkOnSurfaceVariant,
    error = palette.errorContainer, onError = palette.onErrorContainer,
    errorContainer = palette.error.copy(alpha = 0.55f).compositeOver(palette.darkSurface), onErrorContainer = palette.darkOnGround,
    outline = palette.darkOutline, outlineVariant = palette.darkOutline.copy(alpha = 0.45f),
    scrim = Color.Black,
)

private fun semanticColors(palette: AccentPalette, dark: Boolean) = HearthSemanticColors(
    success = if (dark) palette.successContainer else palette.success,
    successContainer = if (dark) palette.success.copy(alpha = 0.5f) else palette.successContainer,
    onSuccessContainer = if (dark) palette.darkOnGround else palette.onSuccessContainer,
    warning = if (dark) palette.warningContainer else palette.warning,
    warningContainer = if (dark) palette.warning.copy(alpha = 0.48f) else palette.warningContainer,
    onWarningContainer = if (dark) palette.darkOnGround else palette.onWarningContainer,
    info = if (dark) palette.infoContainer else palette.info,
    infoContainer = if (dark) palette.info.copy(alpha = 0.48f) else palette.infoContainer,
    onInfoContainer = if (dark) palette.darkOnGround else palette.onInfoContainer,
    ffmpeg = if (dark) palette.secondaryContainer else palette.secondary,
    whisper = if (dark) palette.primaryContainer else palette.primary,
    vosk = if (dark) palette.infoContainer else palette.info,
    onnx = if (dark) palette.warningContainer else palette.warning,
    microphone = if (dark) palette.errorContainer else palette.error,
    onMedia = Color.White,
    mediaScrim = Color.Black,
    detectionPalette = listOf(
        palette.primaryContainer,
        palette.infoContainer,
        palette.warningContainer,
        palette.successContainer,
        palette.errorContainer,
        palette.secondaryContainer,
        palette.tertiary,
        palette.primary,
    ),
)

val AppLightColorScheme = lightScheme(accentPalette(AccentPreset.CLEAN))
val AppDarkColorScheme = darkScheme(accentPalette(AccentPreset.CLEAN))

@Composable
fun HearthTheme(
    themeMode: ThemeMode = rememberThemeMode(),
    accentPreset: AccentPreset = rememberAccentPreset(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = accentPalette(accentPreset)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalHearthColors provides semanticColors(palette, dark),
        LocalColorfulUi provides rememberColorfulUi(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = if (accentPreset in setOf(AccentPreset.CLEAN, AccentPreset.INK, AccentPreset.SKY)) MinimalTypography else Typography,
            content = content,
        )
    }
}

/** Compatibility for existing integrations; new screens should use HearthTheme. */
@Composable
fun FFmpegStudioTheme(
    themeMode: ThemeMode = rememberThemeMode(),
    accentPreset: AccentPreset = rememberAccentPreset(),
    content: @Composable () -> Unit,
) = HearthTheme(themeMode, accentPreset, content)
