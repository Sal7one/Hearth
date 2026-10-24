package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sal7one.common_jni.speech.SpeechProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTextAppearanceConfigTest {
    private val colorKey = intPreferencesKey("text_color_argb_v1")
    private val opacityKey = intPreferencesKey("text_opacity_v1")
    private val amber = 0xffffc857.toInt()

    @Test fun existingInstallsKeepThemeTextAndFullTextOpacity() {
        val prefs = mutablePreferencesOf(intPreferencesKey("background_opacity") to 0)
        val restored = CaptionConfigStore.readFrom(prefs)
        assertNull(restored.textColorArgb)
        assertEquals(100, restored.textOpacity)
        assertEquals(0, restored.backgroundOpacity)
        assertNull(CaptionOverlayConfig().textColorArgb)
    }

    @Test fun customTextAppearanceIsGlobalAcrossOrientationsAndMinimalOrTvPresentation() {
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(
            minimalHeader = true, textColorArgb = amber, textOpacity = 45, backgroundOpacity = 0,
        ), OverlayOrientation.PORTRAIT)

        val landscape = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE)
        assertTrue(landscape.minimalHeader)
        assertEquals(amber, landscape.textColorArgb)
        assertEquals(45, landscape.textOpacity)
        assertEquals(0, landscape.backgroundOpacity)

        CaptionConfigStore.writeInto(prefs, landscape.copy(minimalHeader = false, textOpacity = 65),
            OverlayOrientation.LANDSCAPE)
        for (orientation in OverlayOrientation.entries) {
            val restored = CaptionConfigStore.readFrom(prefs, orientation, television = true)
            assertEquals(amber, restored.textColorArgb)
            assertEquals(65, restored.textOpacity)
            assertFalse(restored.minimalHeader)
        }
    }

    @Test fun customColorsUseOpaqueAlphaAndTextOpacityIsBoundedIndependently() {
        for ((input, expected) in listOf(Int.MIN_VALUE to 10, 9 to 10, 45 to 45, 101 to 100, Int.MAX_VALUE to 100)) {
            val config = CaptionOverlayConfig(textColorArgb = 0x12ffc857, textOpacity = input, backgroundOpacity = 0)
            assertEquals(amber, config.withUiClamp().textColorArgb)
            assertEquals(expected, config.withUiClamp().textOpacity)
            assertEquals(0, config.withUiClamp().backgroundOpacity)

            val prefs = mutablePreferencesOf()
            CaptionConfigStore.writeInto(prefs, config)
            assertEquals(amber, prefs[colorKey])
            assertEquals(expected, prefs[opacityKey])
            assertEquals(amber, CaptionConfigStore.readFrom(prefs).textColorArgb)
            assertEquals(expected, CaptionConfigStore.readFrom(prefs).textOpacity)
        }

        val migrated = CaptionConfigStore.readFrom(mutablePreferencesOf(colorKey to 0x00112233, opacityKey to -5))
        assertEquals(0xff112233.toInt(), migrated.textColorArgb)
        assertEquals(10, migrated.textOpacity)
    }

    @Test fun choosingThemeTextRemovesCustomColorWithoutResettingOpacityOrBackground() {
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(
            textColorArgb = amber, textOpacity = 30, backgroundOpacity = 5,
        ))
        val themeText = CaptionConfigStore.readFrom(prefs).copy(textColorArgb = null)
        CaptionConfigStore.writeInto(prefs, themeText)
        assertNull(prefs[colorKey])
        val restored = CaptionConfigStore.readFrom(prefs)
        assertNull(restored.textColorArgb)
        assertEquals(30, restored.textOpacity)
        assertEquals(5, restored.backgroundOpacity)
    }

    @Test fun everyShapePresetPreservesCustomTextColorAndOpacity() {
        for (minimal in listOf(false, true)) {
            val user = CaptionOverlayConfig(minimalHeader = minimal, textColorArgb = amber, textOpacity = 55)
            val shaped = user.withShape(fontScale = CaptionFontScale.HUGE, backgroundOpacity = 10)
            assertEquals(amber, shaped.textColorArgb)
            assertEquals(55, shaped.textOpacity)
            CaptionPresets.ALL.forEach { preset ->
                val applied = preset.applyTo(user)
                assertEquals(preset.id, amber, applied.textColorArgb)
                assertEquals(preset.id, 55, applied.textOpacity)
                assertTrue(preset.matches(applied))
            }
        }
    }

    @Test fun changingSpeechAndTranslationKeepsTheSavedTextAppearance() {
        val prefs = mutablePreferencesOf()
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(textColorArgb = amber, textOpacity = 40))
        val selected = activateSpeechModel(CaptionConfigStore.readFrom(prefs), SpeechProfile.PHONON_2_5, "phonon-multi")
            .copy(localTranslationModelId = "saved-translator", target = TranslationTarget.ARABIC)
            .withCaptionMode(CaptionMode.TRANSLATE)
        CaptionConfigStore.writeInto(prefs, selected)
        val restored = CaptionConfigStore.readFrom(prefs)
        assertEquals(amber, restored.textColorArgb)
        assertEquals(40, restored.textOpacity)
        assertEquals("phonon-multi", restored.modelId)
        assertEquals("saved-translator", restored.localTranslationModelId)
        assertEquals(TranslationTarget.ARABIC, restored.target)
        assertEquals(CaptionMode.TRANSLATE, restored.mode)
    }

    @Test fun presentationResetRestoresThemeTextAndOpacityWithoutChangingThePipeline() {
        for (orientation in OverlayOrientation.entries) {
            val prefs = mutablePreferencesOf()
            val user = CaptionOverlayConfig(
                textColorArgb = amber, textOpacity = 35, backgroundOpacity = 0, minimalHeader = true,
                engine = CaptionEngineChoice.PHONON, modelId = "phonon-multi", paused = true,
                mode = CaptionMode.TRANSLATE, target = TranslationTarget.ARABIC, streamLanguage = "en",
                localTranslationEnabled = true, localTranslationModelId = "saved-translator",
            )
            CaptionConfigStore.writeInto(prefs, user, orientation)
            val reset = user.resetOverlayPresentation(orientation)
            assertTrue(reset.paused)
            CaptionConfigStore.writeInto(prefs, reset, orientation)
            assertNull(prefs[colorKey])
            val restored = CaptionConfigStore.readFrom(prefs, orientation)
            assertNull(restored.textColorArgb)
            assertEquals(100, restored.textOpacity)
            assertEquals(70, restored.backgroundOpacity)
            assertEquals(user.engine, restored.engine)
            assertEquals(user.modelId, restored.modelId)
            assertEquals(user.localTranslationModelId, restored.localTranslationModelId)
            assertEquals(user.streamLanguage, restored.streamLanguage)
            assertEquals(user.target, restored.target)
            assertEquals(user.mode, restored.mode)
        }
    }

    @Test fun pendingTextPreviewSurvivesOldStoreEmissionsAndAnOlderCommitCannotClearANewerDraft() {
        val preview = OverlaySizePreview()
        val stored = CaptionOverlayConfig(textColorArgb = amber, textOpacity = 100)
        val firstDraft = stored.copy(textColorArgb = 0xff90caf9.toInt(), textOpacity = 40)
        preview.preview(firstDraft)

        val oldEmission = stored.copy(backgroundOpacity = 10)
        val protected = preview.applyTo(oldEmission)
        assertEquals(firstDraft.textColorArgb, protected.textColorArgb)
        assertEquals(40, protected.textOpacity)
        assertEquals("A text preview must not overwrite another saved setting", 10, protected.backgroundOpacity)

        val oldCommit = preview.beginCommit(firstDraft)
        val latestDraft = firstDraft.copy(textColorArgb = null, textOpacity = 25)
        preview.preview(latestDraft)
        preview.finishCommit(oldCommit)
        val stillPreviewing = preview.applyTo(firstDraft)
        assertNull(stillPreviewing.textColorArgb)
        assertEquals(25, stillPreviewing.textOpacity)

        val currentCommit = preview.beginCommit(latestDraft)
        preview.finishCommit(currentCommit)
        assertEquals("Once the current commit finishes, subsequent saved settings are authoritative",
            stored, preview.applyTo(stored))
    }
}
