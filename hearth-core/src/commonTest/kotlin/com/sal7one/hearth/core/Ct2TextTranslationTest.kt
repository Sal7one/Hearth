package com.sal7one.hearth.core

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class Ct2TextTranslationTest {
    private val outputLimit =
        "CT2 translation exceeded its output token limit; incomplete text was not published"
    private val inputLimit = "CT2 input exceeds 1022 tokens; split the text first"

    @Test fun longRegionIsPartitionedWithoutTruncatingOrRepeatingItsSource() {
        val input = "  " + "coffee ".repeat(420).trim() + "  "
        val seen = mutableListOf<String>()
        val output = Ct2TextTranslation.translate(input) { part ->
            seen += part
            "translated-${seen.size}"
        }
        assertTrue(seen.size > 1)
        assertTrue(seen.all { it.length <= 320 })
        assertEquals(input.trim(), seen.joinToString(""))
        assertEquals(seen.indices.joinToString(" ") { "translated-${it + 1}" }, output)
    }

    @Test fun onlyFailingPartsAreSplitAndSuccessfulEarlierPartsAreNotRepeated() {
        val input = "a".repeat(320) + "b".repeat(320)
        val successful = mutableListOf<String>()
        val attempted = mutableListOf<String>()
        val output = Ct2TextTranslation.translate(input) { part ->
            attempted += part
            if (part.first() == 'b' && part.length > 80) throw RuntimeException(outputLimit)
            successful += part
            if (part.first() == 'a') "first" else "next"
        }
        assertEquals(input, successful.joinToString(""))
        assertEquals(1, attempted.count { it.first() == 'a' })
        assertEquals("first next next next next", output)
        assertEquals(8, attempted.size)
    }

    @Test fun ExactNativeInputTokenLimitAlsoRetriesSmallerParts() {
        var attempts = 0
        val successful = mutableListOf<String>()
        val input = "x".repeat(200)
        Ct2TextTranslation.translate(input) { part ->
            attempts++
            if (part.length > 100) throw RuntimeException(inputLimit)
            successful += part
            "complete"
        }
        assertEquals(3, attempts)
        assertEquals(input, successful.joinToString(""))
    }

    @Test fun RecursiveSplitsPreserveInternalSpacesAndNeverCutUnicodeSurrogatePairs() {
        val input = "  " + ("A😀 coffee العربية 中文.\n".repeat(60)) + "  "
        val successful = mutableListOf<String>()
        Ct2TextTranslation.translate(input) { part ->
            assertFalse(part.first().isLowSurrogate())
            assertFalse(part.last().isHighSurrogate())
            if (part.length > 110) throw RuntimeException(outputLimit)
            successful += part
            "مكتمل"
        }
        assertEquals(input.trim(), successful.joinToString(""))
    }

    @Test fun ExhaustedSmallPartHasTypedErrorAndRetainsTheNativeCause() {
        val native = RuntimeException(outputLimit)
        var calls = 0
        val failure = assertFailsWith<TranslationContentLimitException> {
            Ct2TextTranslation.translate("A small region") { calls++; throw native }
        }
        assertEquals(1, calls)
        assertSame(native, failure.cause)
        assertTrue(failure.message.orEmpty().contains("smaller text region"))
    }

    @Test fun SimilarMessagesAndNonContentFailuresArePropagatedWithoutRetry() {
        val failures = listOf(
            RuntimeException("Prefix: $outputLimit"),
            RuntimeException("$inputLimit (different runtime)"),
            RuntimeException("CT2 translation exceeded 20 seconds"),
            RuntimeException("CT2 model integrity mismatch"),
            RuntimeException("HTTP 503: provider unavailable"),
            CancellationException("Cancelled by the caller"),
        )
        failures.forEach { native ->
            var calls = 0
            val error = assertFailsWith<RuntimeException> {
                Ct2TextTranslation.translate("x".repeat(600)) { calls++; throw native }
            }
            assertSame(native, error)
            assertEquals(1, calls)
        }
    }

    @Test fun BlankNativeOutputIsAnErrorAndNeverRetried() {
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> {
            Ct2TextTranslation.translate("x".repeat(600)) { calls++; " \n" }
        }
        assertEquals("CT2 returned empty text", failure.message)
        assertEquals(1, calls)
    }

    @Test fun CancellationAfterACompletedPartSuppressesTheWholeRegionAndLaterCalls() {
        val cancelled = CancellationException("caller stopped the screen request")
        var stop = false
        var calls = 0
        val failure = assertFailsWith<CancellationException> {
            Ct2TextTranslation.translate("x".repeat(600), ensureActive = {
                if (stop) throw cancelled
            }) {
                calls++
                stop = true
                "Do not publish this partial region"
            }
        }
        assertSame(cancelled, failure)
        assertEquals(1, calls)
    }

    @Test fun AtMost64NativeCallsCanBeMadeByOneRegion() {
        val native = RuntimeException(outputLimit)
        var calls = 0
        val failure = assertFailsWith<TranslationContentLimitException> {
            Ct2TextTranslation.translate("x".repeat(3000)) { part ->
                calls++
                if (part.length > 80) throw native
                "complete"
            }
        }
        assertEquals(64, calls)
        assertSame(native, failure.cause)
    }

    @Test fun RequestBudgetStopsBetweenCallsAndDoesNotPublishAnEarlierSuccess() {
        var elapsed = Duration.ZERO
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> {
            Ct2TextTranslation.translateWithinBudget("x".repeat(600), elapsed = { elapsed }) {
                calls++
                elapsed = 21.seconds
                "completed after the request budget"
            }
        }
        assertEquals(1, calls)
        assertEquals(
            "CT2 translation request exceeded 20 seconds; incomplete text was not published",
            failure.message,
        )
    }

    @Test fun OversizedRegionIsTypedAndRejectedBeforeAnyNativeCall() {
        var calls = 0
        assertFailsWith<TranslationContentLimitException> {
            Ct2TextTranslation.translate("x".repeat(3001)) { calls++; "unexpected" }
        }
        assertEquals(0, calls)
    }

    @Test fun BlankSourceIsRejectedBeforeAnyNativeCall() {
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            Ct2TextTranslation.translate(" \n ") { calls++; "unexpected" }
        }
        assertEquals(0, calls)
    }
}
