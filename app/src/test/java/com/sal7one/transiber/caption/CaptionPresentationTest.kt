package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class CaptionPresentationTest {
    private val config = CaptionOverlayConfig(mode = CaptionMode.TRANSLATE, display = CaptionDisplay.TRANSLATED, historyLines = 0)
    private val empty = CaptionReadingSnapshot(emptyList(), "", null)

    @Test fun translationOnlyNeverFallsBackToOriginalForPendingFailedOrBlankTranslation() {
        for (translation in listOf(null, "", "  ")) {
            val source = CaptionLine("Original CC must stay hidden", translation)
            assertTrue(CaptionPresentation.line(source, config).empty)
            val content = CaptionReadingSnapshot(listOf(source), "Source interim", translation)
            assertTrue(CaptionPresentation.partial(content, config).empty)
            assertEquals("", CaptionPresentation.copyText(content, config))
            assertEquals(CaptionEmptyText.WAITING_TRANSLATION, CaptionPresentation.emptyText(content, config))
        }
    }

    @Test fun originalAndBothRetainTheirActualContentWhileTranslationIsPending() {
        val line = CaptionLine("Original", "ترجمة")
        assertEquals("Original", CaptionPresentation.line(line, config.copy(display = CaptionDisplay.ORIGINAL)).text)
        assertEquals("Original\nترجمة", CaptionPresentation.line(line, config.copy(display = CaptionDisplay.BOTH)).text)
        assertEquals("ترجمة", CaptionPresentation.line(line, config).text)
        assertEquals("Original", CaptionPresentation.line(line.copy(translation = null), config.copy(display = CaptionDisplay.BOTH)).text)
    }

    @Test fun directTargetTextIsTranslationEvenWithoutSeparateTranslationField() {
        val direct = CaptionLine("Already translated", primaryRole = CaptionTextRole.TARGET)
        assertEquals("Already translated", CaptionPresentation.line(direct, config).text)
        assertEquals("Already translated", CaptionPresentation.line(direct, config.copy(display = CaptionDisplay.BOTH)).text)
        assertTrue(CaptionPresentation.line(direct, config.copy(display = CaptionDisplay.ORIGINAL)).empty)
        val content = empty.copy(history = listOf(direct))
        assertEquals(CaptionEmptyText.ORIGINAL_UNAVAILABLE,
            CaptionPresentation.emptyText(content, config.copy(display = CaptionDisplay.ORIGINAL)))
        assertEquals("Already translated", CaptionPresentation.partial(empty.copy(partial = direct.original,
            partialRole = CaptionTextRole.TARGET), config).text)
    }

    @Test fun intermediateEnglishCannotMasqueradeAsOriginalOrRequestedTarget() {
        val pivot = CaptionLine("Intermediate English", primaryRole = CaptionTextRole.INTERMEDIATE)
        for (display in CaptionDisplay.entries) assertTrue(CaptionPresentation.line(pivot, config.copy(display = display)).empty)
        assertEquals("العربية", CaptionPresentation.line(pivot.copy(translation = "العربية"), config).text)
        assertEquals("العربية", CaptionPresentation.line(pivot.copy(translation = "العربية"), config.copy(display = CaptionDisplay.BOTH)).text)
    }

    @Test fun translationOnlyPartialDoesNotRequireSourcePartial() {
        val content = empty.copy(partialTranslation = "الترجمة الجزئية")
        assertEquals("الترجمة الجزئية", CaptionPresentation.partial(content, config).text)
        assertEquals("الترجمة الجزئية", CaptionPresentation.copyText(content, config))
    }

    @Test fun providerTranslationArrivingBeforeItsSourceDoesNotClaimOriginalCaptionsAreUnsupported() {
        val sourceDisplay = config.copy(display = CaptionDisplay.ORIGINAL)
        val content = empty.copy(history = listOf(CaptionLine("", "Target run")), partialTranslation = "Target interim")
        assertTrue(CaptionPresentation.partial(content, sourceDisplay).empty)
        assertEquals(CaptionEmptyText.LISTENING, CaptionPresentation.emptyText(content, sourceDisplay))
        assertEquals("Source interim", CaptionPresentation.partial(content.copy(partial = "Source interim"), sourceDisplay).text)
    }

    @Test fun untranslatedLatestUtteranceDoesNotDisplaceTheLastVisibleTranslation() {
        val translated = CaptionLine("Old source", "آخر ترجمة", id = 1)
        val pending = CaptionLine("New source waiting for MT", id = 2)
        val content = empty.copy(history = listOf(translated, pending), partial = "Next source")
        assertEquals(listOf(translated), CaptionPresentation.finals(content, config, reading = false))
        assertEquals("آخر ترجمة", CaptionPresentation.copyText(content, config))
        assertEquals(listOf(translated, pending), content.history)
    }

    @Test fun captionsModeUsesSourceWithoutOverwritingSavedTranslationDisplayPreference() {
        val captions = config.copy(mode = CaptionMode.CAPTIONS)
        assertEquals("Original", CaptionPresentation.line(CaptionLine("Original", "Translation"), captions).text)
        assertEquals(CaptionDisplay.TRANSLATED, captions.display)
        assertEquals("Translation", CaptionPresentation.line(CaptionLine("Original", "Translation"), captions.copy(mode = CaptionMode.TRANSLATE)).text)
    }

    @Test fun heldCopyHonorsSelectionForMixedSourceDirectAndIntermediateHistory() {
        val content = CaptionReadingSnapshot(listOf(CaptionLine("Source one", "Target one"),
            CaptionLine("Hidden pending source"), CaptionLine("Direct target", primaryRole = CaptionTextRole.TARGET),
            CaptionLine("Hidden pivot", primaryRole = CaptionTextRole.INTERMEDIATE)), "", "Partial target")
        assertEquals("Target one\nDirect target\nPartial target", CaptionPresentation.copyText(content, config))
        assertEquals("Source one\nHidden pending source", CaptionPresentation.copyText(content, config.copy(display = CaptionDisplay.ORIGINAL)))
    }

    @Test fun primaryRoleRequiresExplicitFinalTargetEvidence() {
        for (route in CaptionTranslationRoute.entries) {
            val expected = when (route) {
                CaptionTranslationRoute.ENGLISH_TASK -> CaptionTextRole.TARGET
                CaptionTranslationRoute.ENGLISH_PIVOT -> CaptionTextRole.INTERMEDIATE
                else -> CaptionTextRole.SOURCE
            }
            assertEquals(expected, captionPrimaryRole(route, false))
            assertEquals(CaptionTextRole.TARGET, captionPrimaryRole(route, true))
        }
        assertEquals(CaptionEmptyText.LISTENING, CaptionPresentation.emptyText(empty, config))
    }
}
