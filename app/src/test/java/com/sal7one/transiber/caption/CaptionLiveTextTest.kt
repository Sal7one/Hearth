package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class CaptionLiveTextTest {
    private val captions = CaptionOverlayConfig(mode = CaptionMode.CAPTIONS, historyLines = 4)
    private val translated = captions.copy(mode = CaptionMode.TRANSLATE, display = CaptionDisplay.TRANSLATED)

    @Test fun shortFinalsAndPartialFlowIntoOneParagraphWithoutInventedLineBreaks() {
        val history = listOf(CaptionLine("software", id = 1), CaptionLine("And the same", id = 2))
        val content = CaptionReadingSnapshot(history, "I just wish we could go", null)
        val paragraph = CaptionLiveText.paragraphs(content, captions).single()
        assertEquals("software And the same I just wish we could go", paragraph.text)
        assertEquals(listOf(true, true, false), paragraph.chunks.map { it.faded })
        assertFalse(paragraph.translated)
        assertEquals(history, content.history)
    }

    @Test fun latestFinalRemainsHighlightedAndPreviousSegmentLimitStillApplies() {
        val content = CaptionReadingSnapshot((1..12).map { CaptionLine("segment $it", id = it.toLong()) }, "", null)
        val two = CaptionLiveText.paragraphs(content, captions.copy(historyLines = 1)).single()
        assertEquals("segment 11 segment 12", two.text)
        assertEquals(listOf(true, false), two.chunks.map { it.faded })
        assertEquals("segment 12", CaptionLiveText.paragraphs(content, captions.copy(historyLines = 0)).single().text)
        assertEquals(9, CaptionLiveText.paragraphs(content, captions.copy(historyLines = 100)).single().chunks.size)
        assertEquals(12, content.history.size)
    }

    @Test fun bilingualModeKeepsSourceAndTargetPairedInChronologicalParagraphs() {
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("First sentence.", "الجملة الأولى.", id = 1),
            CaptionLine("Second sentence.", "الجملة الثانية.", id = 2),
        ), "Next source", "الترجمة التالية")
        val paragraphs = CaptionLiveText.paragraphs(content, translated.copy(display = CaptionDisplay.BOTH))
        assertEquals(listOf(false, true, false, true, false, true), paragraphs.map { it.translated })
        assertEquals(listOf("First sentence.", "الجملة الأولى.", "Second sentence.", "الجملة الثانية.",
            "Next source", "الترجمة التالية"), paragraphs.map { it.text })
        assertEquals(listOf(true, true, true, true, false, false), paragraphs.map { it.chunks.single().faded })
    }

    @Test fun bilingualNewestUntranslatedSourceStaysAfterTheOlderTarget() {
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("Older original", "ترجمة قديمة طويلة", id = 1),
        ), "Newest source partial", null)
        val paragraphs = CaptionLiveText.paragraphs(content, translated.copy(display = CaptionDisplay.BOTH))
        assertEquals(listOf("Older original", "ترجمة قديمة طويلة", "Newest source partial"), paragraphs.map { it.text })
        assertEquals(listOf(false, true, false), paragraphs.map { it.translated })
        assertFalse(paragraphs.last().chunks.single().faded)
    }

    @Test fun translationOnlyPreservesLastTargetWhilePendingAndFailedSourcesStayHidden() {
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("Source one", "الترجمة الأولى", id = 1),
            CaptionLine("Failed translation source", " ", id = 2),
            CaptionLine("Pending translation source", id = 3),
        ), "Source partial", null)
        assertEquals(listOf(CaptionLiveParagraph(true, listOf(CaptionLiveChunk("الترجمة الأولى", false)))),
            CaptionLiveText.paragraphs(content, translated.copy(historyLines = 0)))
    }

    @Test fun temporarySourceFallbackKeepsItsOwnTrackThenIsReplacedByActualTranslation() {
        val config = translated.copy(showOriginalWhileTranslating = true)
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("Old source", "الترجمة السابقة", id = 1),
            CaptionLine("Pending source", id = 2),
        ), "", null)
        val pending = CaptionLiveText.paragraphs(content, config)
        assertEquals(listOf("الترجمة السابقة", "Pending source"), pending.map { it.text })
        assertEquals(listOf(true, false), pending.map { it.translated })
        val complete = content.copy(history = listOf(content.history[0], content.history[1].copy(translation = "وصلت الترجمة")))
        val updated = CaptionLiveText.paragraphs(complete, config)
        assertEquals(listOf(true), updated.map { it.translated })
        assertEquals("الترجمة السابقة وصلت الترجمة", updated.single().text)
        assertNull(content.history[1].translation)
    }

    @Test fun outOfOrderTranslationsDoNotMoveNewerPendingCaptionsAboveOlderText() {
        val config = translated.copy(showOriginalWhileTranslating = true)
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("Source one", "الأولى", id = 1),
            CaptionLine("Waiting two", id = 2),
            CaptionLine("Source three", "الثالثة", id = 3),
        ), "Waiting partial", null)
        val paragraphs = CaptionLiveText.paragraphs(content, config)
        assertEquals(listOf("الأولى", "Waiting two", "الثالثة", "Waiting partial"), paragraphs.map { it.text })
        assertEquals(listOf(true, false, true, false), paragraphs.map { it.translated })
        assertFalse(paragraphs.last().chunks.single().faded)
    }

    @Test fun directTargetsAndIntermediateTextKeepTheirProvenanceRules() {
        val content = CaptionReadingSnapshot(listOf(
            CaptionLine("Hidden English pivot", primaryRole = CaptionTextRole.INTERMEDIATE, id = 1),
            CaptionLine("مخرجات مباشرة", primaryRole = CaptionTextRole.TARGET, id = 2),
        ), "Hidden pivot partial", null, CaptionTextRole.INTERMEDIATE)
        assertEquals("مخرجات مباشرة", CaptionLiveText.paragraphs(content, translated).single().text)
        assertTrue(CaptionLiveText.paragraphs(content, captions).isEmpty())
    }

    @Test fun partialOnlyAndRepeatedRealFinalsArePreservedWithoutDeduplication() {
        assertEquals("جزئي", CaptionLiveText.paragraphs(
            CaptionReadingSnapshot(emptyList(), "", "جزئي"), translated).single().text)
        val repeated = CaptionReadingSnapshot(listOf(CaptionLine("Yes", id = 1), CaptionLine("Yes", id = 2)), "", null)
        assertEquals("Yes Yes", CaptionLiveText.paragraphs(repeated, captions).single().text)
        assertTrue(CaptionLiveText.paragraphs(CaptionReadingSnapshot(emptyList(), "", null), captions).isEmpty())
    }
}
