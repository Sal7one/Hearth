package com.sal7one.transiber.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionDecisionTest {

    @Test
    fun freshSessionNeverPromotesStaleText() {
        val decision = decidePromotion(
            partial = "",
            lastPartial = "",
            lastPartialAtMs = 0L,
            emptySinceMs = null,
            now = 100_000L,
            trustVoiceEnd = true,
        )
        assertEquals(PromotionDecision.Hold, decision)
    }

    @Test
    fun whisperPromotesAfterVoiceEndGrace() {
        val now = 100_000L
        val lastPartialAtMs = now - 10_000L
        // Just went empty: still within the grace window.
        assertEquals(
            PromotionDecision.Hold,
            decidePromotion(
                partial = "",
                lastPartial = "hello",
                lastPartialAtMs = lastPartialAtMs,
                emptySinceMs = now,
                now = now,
                trustVoiceEnd = true,
            ),
        )
        // Grace elapsed: promote.
        assertEquals(
            PromotionDecision.Promote("hello"),
            decidePromotion(
                partial = "",
                lastPartial = "hello",
                lastPartialAtMs = lastPartialAtMs,
                emptySinceMs = now,
                now = now + VOICE_END_GRACE_MS,
                trustVoiceEnd = true,
            ),
        )
    }

    @Test
    fun firstEmptyObservationDoesNotPromoteImmediately() {
        val now = 100_000L
        assertEquals(
            PromotionDecision.Hold,
            decidePromotion(
                partial = "",
                lastPartial = "hello",
                lastPartialAtMs = now - 10_000L,
                emptySinceMs = null,
                now = now,
                trustVoiceEnd = true,
            ),
        )
    }

    @Test
    fun nonWhisperFallsBackToTheSilenceClock() {
        val now = 100_000L
        assertEquals(
            PromotionDecision.Hold,
            decidePromotion(
                partial = "",
                lastPartial = "hello",
                lastPartialAtMs = now - SILENCE_PROMOTE_MS + 1,
                emptySinceMs = now,
                now = now,
                trustVoiceEnd = false,
            ),
        )
        assertEquals(
            PromotionDecision.Promote("hello"),
            decidePromotion(
                partial = "",
                lastPartial = "hello",
                lastPartialAtMs = now - SILENCE_PROMOTE_MS,
                emptySinceMs = now,
                now = now,
                trustVoiceEnd = false,
            ),
        )
    }

    @Test
    fun nonPrefixChangeTracksStableInsteadOfPromoting() {
        // In-window rewrites must NOT promote (that flooded history with
        // A,B,A,B alternates); the stable-display lock holds the shown line.
        val decision = decidePromotion(
            partial = "goodbye",
            lastPartial = "hello",
            lastPartialAtMs = 0L,
            emptySinceMs = null,
            now = 100_000L,
            trustVoiceEnd = true,
        )
        assertEquals(PromotionDecision.TrackStable("goodbye"), decision)
    }

    @Test
    fun prefixGrowthTracksTheNewPartial() {
        assertEquals(
            PromotionDecision.Track("hello world"),
            decidePromotion(
                partial = "hello world",
                lastPartial = "hello",
                lastPartialAtMs = 0L,
                emptySinceMs = null,
                now = 100_000L,
                trustVoiceEnd = true,
            ),
        )
    }

    @Test
    fun firstPartialTracksWithoutPromoting() {
        assertEquals(
            PromotionDecision.Track("hello"),
            decidePromotion(
                partial = "hello",
                lastPartial = "",
                lastPartialAtMs = 0L,
                emptySinceMs = null,
                now = 100_000L,
                trustVoiceEnd = true,
            ),
        )
    }

    @Test
    fun overlongPartialPromotesItsHeadAndKeepsTheTail() {
        // Vosk under continuous audio never endpoints: the partial keeps
        // growing. Past the budget the head rolls into history...
        val words = (1..60).joinToString(" ") { "word$it" } // 468 chars
        val decision = decidePromotion(
            partial = words,
            lastPartial = "",
            lastPartialAtMs = 0L,
            emptySinceMs = null,
            now = 100_000L,
            trustVoiceEnd = false,
        )
        val promoteAndTrack = decision as PromotionDecision.PromoteAndTrack
        // Split at the last space: promote everything but the final word.
        assertEquals(words.substringBeforeLast(' '), promoteAndTrack.promote)
        assertEquals("word60", promoteAndTrack.track)
        org.junit.Assert.assertTrue(promoteAndTrack.track.length < PARTIAL_MAX_CHARS)
    }

    @Test
    fun overlongSpacelessPartialStillSplitsWithoutBreakingSurrogates() {
        // CJK has no spaces; the fallback cut must still bound the tail and
        // never split a surrogate pair.
        val cjk = "字".repeat(PARTIAL_MAX_CHARS + 20)
        val decision = decidePromotion(
            partial = cjk,
            lastPartial = "",
            lastPartialAtMs = 0L,
            emptySinceMs = null,
            now = 100_000L,
            trustVoiceEnd = false,
        )
        val promoteAndTrack = decision as PromotionDecision.PromoteAndTrack
        org.junit.Assert.assertTrue(promoteAndTrack.track.length < PARTIAL_MAX_CHARS)
        org.junit.Assert.assertTrue(promoteAndTrack.track.isNotEmpty())
        org.junit.Assert.assertTrue(promoteAndTrack.promote.isNotEmpty())
        // '字' is BMP (no surrogates): all characters survive the split.
        assertEquals(cjk.length, promoteAndTrack.promote.length + promoteAndTrack.track.length)
    }

    @Test
    fun partialUnderTheBudgetJustTracks() {
        val short = (1..25).joinToString(" ") { "word$it" } // < 200 chars
        assertEquals(
            PromotionDecision.Track(short),
            decidePromotion(
                partial = short,
                lastPartial = "",
                lastPartialAtMs = 0L,
                emptySinceMs = null,
                now = 100_000L,
                trustVoiceEnd = true,
            ),
        )
    }

    @Test
    fun attachByIdTargetsExactLineEvenWithDuplicateText() {
        val history = listOf(
            CaptionLine(original = "hello", id = 1L),
            CaptionLine(original = "hello", id = 2L),
        )
        val updated = attachTranslationById(history, lineId = 2L, translated = "مرحبا")
        assertEquals("hello", updated[0].original)
        assertNull(updated[0].translation)
        assertEquals("مرحبا", updated[1].translation)
    }

    @Test
    fun attachByIdDoesNotOverwriteAnExistingTranslation() {
        val history = listOf(CaptionLine(original = "hello", translation = "old", id = 1L))
        val updated = attachTranslationById(history, lineId = 1L, translated = "new")
        assertEquals("old", updated[0].translation)
    }
}

class StripPromotedPrefixTest {

    @Test
    fun stripsExactPromotedPrefix() {
        assertEquals("more words", stripPromotedPrefix("hello world more words", "hello world"))
    }

    @Test
    fun fullyContainedPartialBecomesEmpty() {
        assertEquals("", stripPromotedPrefix("hello world", "hello world"))
    }

    @Test
    fun midWordPrefixIsNotStripped() {
        // "worldly" shares a prefix but is a different word.
        assertEquals("worldly matters", stripPromotedPrefix("worldly matters", "world"))
    }

    @Test
    fun tailEchoIsStrippedAtWordBoundary() {
        // Whisper tail-window re-emits the END of the promoted line.
        assertEquals("again", stripPromotedPrefix("world again", "hello world"))
    }

    @Test
    fun tailEchoMidWordIsKept() {
        assertEquals("worldly", stripPromotedPrefix("worldly", "hello world"))
    }

    @Test
    fun nullOrBlankPromotedKeepsPartial() {
        assertEquals("hello", stripPromotedPrefix("hello", null))
        assertEquals("hello", stripPromotedPrefix("hello", ""))
    }

    @Test
    fun unrelatedPartialIsKept() {
        assertEquals("something else", stripPromotedPrefix("something else", "hello world"))
    }
}

class TranslationAttachTest {

    @Test
    fun attachSetsTranslationOnceAndIsIdempotent() {
        val line = CaptionLine(id = 7L, original = "hello", translation = null)
        val once = attachTranslationById(listOf(line), 7L, "مرحبا")
        assertEquals("مرحبا", once.first().translation)
        // A duplicate/late attach must NOT overwrite the first translation.
        val twice = attachTranslationById(once, 7L, "different")
        assertEquals("مرحبا", twice.first().translation)
    }

    @Test
    fun attachNeverTouchesOtherLinesOrEmptyHistory() {
        val a = CaptionLine(id = 1L, original = "one", translation = null)
        val b = CaptionLine(id = 2L, original = "two", translation = null)
        val out = attachTranslationById(listOf(a, b), 2L, "اثنان")
        assertNull(out.first().translation)
        assertEquals("اثنان", out.last().translation)
        assertEquals(emptyList<CaptionLine>(), attachTranslationById(emptyList(), 1L, "x"))
    }
}

class StableLiveTextTest {

    @Test
    fun firstPartialAfterEmptyIsAccepted() {
        assertEquals("hello", stableLiveText("hello", ""))
    }

    @Test
    fun strictExtensionIsAccepted() {
        assertEquals("hello world again", stableLiveText("hello world again", "hello world"))
        assertEquals("hello worlds", stableLiveText("hello worlds", "hello world"))
        assertEquals("hello world.", stableLiveText("hello world.", "hello world"))
    }

    @Test
    fun regressionIsHeld() {
        assertEquals("hello world", stableLiveText("hello worl", "hello world"))
    }

    @Test
    fun equalLengthAlternateIsHeld() {
        assertEquals("hello world", stableLiveText("hello worls", "hello world"))
        assertEquals("hello world.", stableLiveText("hello world,", "hello world."))
    }

    @Test
    fun identicalTextPassesThrough() {
        assertEquals("hello world", stableLiveText("hello world", "hello world"))
    }
}
