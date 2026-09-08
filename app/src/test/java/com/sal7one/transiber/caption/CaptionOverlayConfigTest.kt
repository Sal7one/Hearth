package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionOverlayConfigTest {

    @Test
    fun englishTargetKeepsVoskAsTheFastPath() {
        val config = CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE,
            target = TranslationTarget.ENGLISH,
            engine = CaptionEngineChoice.VOSK,
        )
        // Vosk + English target is the identity fast path: instant English
        // captions, no forced whisper switch. The controller shows an honest
        // notice when the Vosk model's language is not English.
        assertEquals(CaptionEngineChoice.VOSK, config.effectiveEngine)
        assertTrue(config.translationIsIdentity)
        assertTrue(config.translationRtl.not())
    }

    @Test
    fun englishTargetWithWhisperUsesTheOnePassTask() {
        val config = CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE,
            target = TranslationTarget.ENGLISH,
            engine = CaptionEngineChoice.WHISPER,
        )
        assertEquals(CaptionEngineChoice.WHISPER, config.effectiveEngine)
        assertFalse(config.translationIsIdentity)
    }

    @Test
    fun arabicTranslationKeepsTheCaptionEngineAndSetsRtl() {
        val config = CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE,
            target = TranslationTarget.ARABIC,
            engine = CaptionEngineChoice.VOSK,
        )
        // Second-stage translation: caption engine is whatever the user chose.
        assertEquals(CaptionEngineChoice.VOSK, config.effectiveEngine)
        assertTrue(config.translationRtl)
    }

    @Test
    fun chineseTargetIsSupportedLeftToRightAndFailsClosedLikeArabic() {
        val config = CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE,
            target = TranslationTarget.CHINESE,
            engine = CaptionEngineChoice.WHISPER,
        )
        // Second-stage target: keeps whisper captioning, no RTL, honest gating.
        assertEquals(CaptionEngineChoice.WHISPER, config.effectiveEngine)
        assertFalse(config.translationRtl)
        assertFalse(config.translationIsIdentity)
    }

    @Test
    fun captionsModeIsNeverRtlAndKeepsTheChosenEngine() {
        val config = CaptionOverlayConfig(
            mode = CaptionMode.CAPTIONS,
            target = TranslationTarget.ARABIC,
            engine = CaptionEngineChoice.VOSK,
        )
        assertEquals(CaptionEngineChoice.VOSK, config.effectiveEngine)
        assertFalse(config.translationRtl)
    }

    @Test
    fun uiValuesAreClampedIntoTheirDocumentedRanges() {
        val clamped = CaptionOverlayConfig(
            widthPercent = 500,
            maxHeightPercent = 500,
            historyLines = -3,
            backgroundOpacity = 1400,
        ).withUiClamp()

        assertEquals(100, clamped.widthPercent)
        assertEquals(60, clamped.maxHeightPercent)
        assertEquals(0, clamped.historyLines)
        assertEquals(100, clamped.backgroundOpacity)
        assertTrue(clamped.widthPercent >= 50)
        assertTrue(clamped.maxHeightPercent >= 20)
    }

    @Test
    fun everyEnumHasAUserFacingLabel() {
        CaptionMode.entries.forEach { assertTrue(it.label.isNotBlank()) }
        CaptionSource.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.explanation.isNotBlank())
        }
        CaptionEngineChoice.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.explanation.isNotBlank())
        }
        CaptionTheme.entries.forEach { assertTrue(it.label.isNotBlank()) }
        CaptionAnchor.entries.forEach { assertTrue(it.label.isNotBlank()) }
        CaptionFontScale.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.multiplier > 0f)
        }
        TranslationTarget.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.languageTag.isNotBlank())
        }
        CaptionDisplay.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }

    @Test
    fun defaultThemeIsDark() {
        assertEquals(CaptionTheme.DARK, CaptionOverlayConfig().theme)
    }

    @Test
    fun themeSurvivesTheCopiesPresetsUse() {
        CaptionTheme.entries.forEach { theme ->
            val user = CaptionOverlayConfig(
                theme = theme,
                mode = CaptionMode.TRANSLATE,
                target = TranslationTarget.ARABIC,
            )

            // A shape-only restyle (what presets apply) must not touch theme.
            val shaped = user.withShape(
                anchor = CaptionAnchor.TOP,
                widthPercent = 60,
                fontScale = CaptionFontScale.HUGE,
                historyLines = 8,
                showPartial = false,
                backgroundOpacity = 20,
            )
            assertEquals(theme, shaped.theme)
            assertEquals(CaptionAnchor.TOP, shaped.anchor)
            assertEquals(CaptionFontScale.HUGE, shaped.fontScale)

            // The shape helper also leaves capture/translation semantics alone.
            assertEquals(CaptionMode.TRANSLATE, shaped.mode)
            assertEquals(TranslationTarget.ARABIC, shaped.target)

            // A plain copy of a non-theme field preserves theme too.
            assertEquals(theme, user.copy(widthPercent = 75).theme)
        }
    }

    @Test
    fun storeRoundTripsTheUsersTheme() {
        CaptionTheme.entries.forEach { theme ->
            val prefs = mutablePreferencesOf()
            CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(theme = theme))
            assertEquals(theme, CaptionConfigStore.readFrom(prefs).theme)
        }
    }

    @Test
    fun storeKeepsThemeWhenOtherFieldsChange() {
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(
            prefs,
            CaptionOverlayConfig(theme = CaptionTheme.LIGHT, widthPercent = 90),
        )

        // Simulate the store's update() path: change a shape field, leave theme.
        val next = CaptionConfigStore.readFrom(prefs).copy(widthPercent = 55)
        CaptionConfigStore.writeInto(prefs, next)

        val restored = CaptionConfigStore.readFrom(prefs)
        assertEquals(CaptionTheme.LIGHT, restored.theme)
        assertEquals(55, restored.widthPercent)
    }

    @Test
    fun storeRoundTripsVoiceOutputSettings() {
        CaptionSpeakerChoice.entries.forEach { choice ->
            val prefs = mutablePreferencesOf()
            CaptionConfigStore.writeInto(
                prefs,
                CaptionOverlayConfig(speakCaptions = true, speakerChoice = choice),
            )
            val restored = CaptionConfigStore.readFrom(prefs)
            assertEquals(true, restored.speakCaptions)
            assertEquals(choice, restored.speakerChoice)
        }
    }

    @Test
    fun fontScaleBoostIsOneSaturatingStepForTv() {
        assertEquals(CaptionFontScale.LARGE, CaptionFontScale.NORMAL.boosted())
        assertEquals(CaptionFontScale.HUGE, CaptionFontScale.HUGE.boosted())
        assertEquals(CaptionFontScale.NORMAL, CaptionFontScale.SMALL.boosted())
    }
}

class TranslationLayerTest {

    private val layer = TranslationLayer()

    @Test
    fun englishIsAvailableTodayViaTheWhisperTask() {
        assertTrue(layer.isAvailable(TranslationTarget.ENGLISH))
    }

    @Test
    fun arabicFailsClosedWithAnActionableReason() {
        assertFalse(layer.isAvailable(TranslationTarget.ARABIC))
        val result = layer.translate("hello", TranslationTarget.ARABIC)
        assertTrue(result is TranslationResult.Unavailable)
        val reason = (result as TranslationResult.Unavailable).reason
        // The user must be told what to do, not just "failed".
        assertTrue(reason.contains("translation model", ignoreCase = true))
        assertTrue(reason.contains("English", ignoreCase = true))
    }

    @Test
    fun emptyInputNeverReachesABackend() {
        val result = layer.translate("   ", TranslationTarget.ENGLISH)
        assertTrue(result is TranslationResult.Unavailable)
    }

    @Test
    fun whisperTaskIsMarkedAsHandlingEnglishOnly() {
        assertTrue(TranslationLayer.whisperHandlesTarget(TranslationTarget.ENGLISH))
        assertFalse(TranslationLayer.whisperHandlesTarget(TranslationTarget.ARABIC))
    }

    @Test
    fun aFakeBackendCanBePluggedInForArabic() {
        val arabic = object : Translator {
            override fun isAvailable(target: TranslationTarget) = target == TranslationTarget.ARABIC
            override fun unavailabilityReason(target: TranslationTarget) = "never"
            override fun translate(text: String, target: TranslationTarget) =
                TranslationResult.Translated("مرحبا")
        }
        val custom = TranslationLayer(backends = listOf(WhisperTaskTranslator(), arabic))
        assertTrue(custom.isAvailable(TranslationTarget.ARABIC))
        assertEquals(
            TranslationResult.Translated("مرحبا"),
            custom.translate("hello", TranslationTarget.ARABIC),
        )
    }
}

class CaptionPresetsTest {

    @Test
    fun everyPresetHasALabelDescriptionAndUniqueId() {
        CaptionPresets.ALL.forEach { preset ->
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.label.isNotBlank())
            assertTrue(preset.description.isNotBlank())
        }
        assertEquals(
            CaptionPresets.ALL.size,
            CaptionPresets.ALL.map { it.id }.distinct().size,
        )
    }

    @Test
    fun applyingAPresetPreservesThemeAndAppliesShape() {
        CaptionPresets.ALL.forEach { preset ->
            val applied = preset.applyTo(CaptionOverlayConfig(theme = CaptionTheme.HIGH_CONTRAST))
            assertEquals(CaptionTheme.HIGH_CONTRAST, applied.theme)
            assertEquals(preset.anchor, applied.anchor)
            assertEquals(preset.widthPercent, applied.widthPercent)
            assertEquals(preset.xOffsetPx, applied.xOffsetPx)
            assertEquals(preset.yOffsetPx, applied.yOffsetPx)
            assertEquals(preset.fontScale, applied.fontScale)
            assertEquals(preset.historyLines, applied.historyLines)
            assertEquals(preset.showPartial, applied.showPartial)
            assertEquals(preset.backgroundOpacity, applied.backgroundOpacity)
            assertEquals(preset.tapThrough, applied.tapThrough)
            assertEquals(preset.paused, applied.paused)
        }
    }

    @Test
    fun selectionDetectionFindsTheAppliedPreset() {
        val base = CaptionOverlayConfig()
        CaptionPresets.ALL.forEach { preset ->
            assertEquals(preset, CaptionPresets.selected(preset.applyTo(base)))
        }
    }

    @Test
    fun selectionDetectionIgnoresTheme() {
        val preset = CaptionPresets.SubtleChip
        CaptionTheme.entries.forEach { theme ->
            val applied = preset.applyTo(CaptionOverlayConfig(theme = theme))
            assertEquals(preset, CaptionPresets.selected(applied))
        }
    }

    @Test
    fun selectionDetectionReturnsNullAfterManualShapeEdit() {
        val edited = CaptionPresets.BottomStrip.applyTo(CaptionOverlayConfig())
            .copy(widthPercent = 55)
        assertNull(CaptionPresets.selected(edited))
    }
}
