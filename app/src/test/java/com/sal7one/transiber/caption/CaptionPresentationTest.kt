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

    @Test fun optedInSourceFinalAndPartialAreReplacedWhenTranslationArrives() {
        val enabled = config.copy(showOriginalWhileTranslating = true)
        for (missing in listOf(null, "", "  ")) {
            val pending = CaptionLine("Original CC", missing, id = 42)
            assertEquals(CaptionVisiblePair(original = "Original CC"), CaptionPresentation.line(pending, enabled))
            assertEquals(CaptionVisiblePair(translation = "الترجمة"),
                CaptionPresentation.line(pending.copy(translation = "الترجمة"), enabled))
            val interim = empty.copy(partial = "Source interim", partialTranslation = missing)
            assertEquals("Source interim", CaptionPresentation.partial(interim, enabled).text)
            assertEquals(CaptionVisiblePair(translation = "الترجمة الجزئية"),
                CaptionPresentation.partial(interim.copy(partialTranslation = "الترجمة الجزئية"), enabled))
        }
    }

    @Test fun choiceUpdatesExistingHistoryAndHeldCopyWithoutChangingItsRawContent() {
        val content = CaptionReadingSnapshot(listOf(CaptionLine("Source one", "Target one"),
            CaptionLine("Pending source", id = 2), CaptionLine("Direct target", primaryRole = CaptionTextRole.TARGET),
            CaptionLine("Hidden pivot", primaryRole = CaptionTextRole.INTERMEDIATE)), "Source interim", null)
        val enabled = config.copy(showOriginalWhileTranslating = true)
        assertEquals("Target one\nPending source\nDirect target\nSource interim", CaptionPresentation.copyText(content, enabled))
        assertEquals("Target one\nDirect target", CaptionPresentation.copyText(content, config))
        val pending = content.history[1]
        val current = empty.copy(history = content.history.take(2))
        assertEquals(listOf(pending), CaptionPresentation.finals(current, enabled, reading = false))
        assertEquals(listOf(current.history.first()), CaptionPresentation.finals(current, config, reading = false))
        assertEquals(listOf(CaptionLine("Source one", "Target one"), pending), current.history)
    }

    @Test fun optingInNeverExposesIntermediateOrRelabelsDirectTargetAsOriginal() {
        val enabled = config.copy(showOriginalWhileTranslating = true)
        val pivot = CaptionLine("Hidden intermediate English", primaryRole = CaptionTextRole.INTERMEDIATE)
        assertTrue(CaptionPresentation.line(pivot, enabled).empty)
        assertTrue(CaptionPresentation.partial(empty.copy(partial = pivot.original,
            partialRole = pivot.primaryRole), enabled).empty)
        assertEquals(CaptionVisiblePair(translation = "Requested target"),
            CaptionPresentation.line(pivot.copy(translation = "Requested target"), enabled))
        val direct = CaptionLine("Direct target", primaryRole = CaptionTextRole.TARGET)
        assertEquals(CaptionVisiblePair(translation = "Direct target"), CaptionPresentation.line(direct, enabled))
        assertEquals(CaptionVisiblePair(translation = "Direct target"),
            CaptionPresentation.partial(empty.copy(partial = direct.original, partialRole = direct.primaryRole), enabled))
    }

    @Test fun pendingSourceChoiceDoesNotChangeOtherDisplayModesOrCaptionsMode() {
        val source = CaptionLine("Source", "Target")
        for (display in listOf(CaptionDisplay.ORIGINAL, CaptionDisplay.BOTH)) {
            val selected = config.copy(display = display)
            for (line in listOf(source, source.copy(translation = null))) {
                assertEquals(CaptionPresentation.line(line, selected),
                    CaptionPresentation.line(line, selected.copy(showOriginalWhileTranslating = true)))
            }
        }
        val captions = config.copy(mode = CaptionMode.CAPTIONS, showOriginalWhileTranslating = true)
        assertEquals(CaptionVisiblePair(original = "Source"), CaptionPresentation.line(source, captions))
        assertTrue(CaptionPresentation.line(CaptionLine("  "), captions.copy(mode = CaptionMode.TRANSLATE)).empty)
    }

    @Test fun temporarySourceRespectsHiddenPartialsAndIndependentProviderRuns() {
        val enabled = config.copy(showOriginalWhileTranslating = true, showPartial = false)
        val state = CaptionEngineController.State(history = listOf(
            CaptionLine("", "Independent target run", id = 1), CaptionLine("Independent source run", id = 2)),
            partial = "Hidden source interim")
        val held = CaptionReading.snapshot(state, enabled.showPartial)
        assertTrue(CaptionPresentation.partial(held, enabled).empty)
        assertEquals("Independent target run\nIndependent source run", CaptionPresentation.copyText(held, enabled))
        assertEquals("Independent target run", CaptionPresentation.copyText(held, config))
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
