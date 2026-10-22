package com.sal7one.hearth.desktop.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CloseOwnersTest {
    @Test fun attemptsEveryOwnerOnceAndPreservesOriginalFailure() {
        val calls = mutableListOf<Int>()
        val first = IllegalStateException("translation destroy failed")
        val later = IllegalArgumentException("voice destroy failed")
        val error = assertFailsWith<IllegalStateException> {
            closeOwners({ calls += 1; throw first }, { calls += 2 }, { calls += 3; throw later }, { calls += 4 })
        }
        assertEquals(listOf(1, 2, 3, 4), calls)
        assertSame(first, error)
        assertSame(later, error.suppressed.single())
    }
    @Test fun repeatedSameFailureDoesNotPreventRemainingCleanup() {
        val error = IllegalStateException("shared failure")
        var finalClosed = false
        assertSame(error, assertFailsWith<IllegalStateException> {
            closeOwners({ throw error }, { throw error }, { finalClosed = true })
        })
        assertEquals(true, finalClosed)
    }
}
