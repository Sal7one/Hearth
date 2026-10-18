package com.sal7one.transiber.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TypedTranslationRequestPolicyTest {
    @Test fun manualCpuRetryWaitsForSavedPreferenceThenRequestsExactlyOneImmediateTranslation() {
        val policy = TypedTranslationRequestPolicy()
        assertEquals(TypedTranslationRequestPolicy.Action.CLEAR, policy.takeAction(false, 4))
        policy.requestImmediateAfter(5)
        // Compose can observe the retry click before collecting the new settings revision.
        assertEquals(TypedTranslationRequestPolicy.Action.KEEP, policy.takeAction(false, 4))
        assertEquals(TypedTranslationRequestPolicy.Action.IMMEDIATE, policy.takeAction(false, 5))
        // Later input/settings changes still obey manual mode; the retry did not enable it.
        assertEquals(TypedTranslationRequestPolicy.Action.CLEAR, policy.takeAction(false, 6))
    }

    @Test fun automaticCpuRetryBypassesDebounceAndKeepsAutomaticMode() {
        val policy = TypedTranslationRequestPolicy()
        policy.requestImmediateAfter(8)
        assertEquals(TypedTranslationRequestPolicy.Action.KEEP, policy.takeAction(true, 7))
        assertEquals(TypedTranslationRequestPolicy.Action.IMMEDIATE, policy.takeAction(true, 8))
        // A later effect caused by new text returns to the normal debounce policy.
        assertEquals(TypedTranslationRequestPolicy.Action.DEBOUNCED, policy.takeAction(true, 8))
    }

    @Test fun latestRetryCanBeConsumedWhenStateFlowCoalescesSettingsRevisions() {
        val policy = TypedTranslationRequestPolicy()
        policy.requestImmediateAfter(10)
        policy.requestImmediateAfter(11)
        assertEquals(TypedTranslationRequestPolicy.Action.KEEP, policy.takeAction(false, 10))
        assertEquals(TypedTranslationRequestPolicy.Action.IMMEDIATE, policy.takeAction(false, 12))
        assertEquals(TypedTranslationRequestPolicy.Action.CLEAR, policy.takeAction(false, 12))
    }
}
