package com.sal7one.transiber.shortcuts

import org.junit.Assert.*
import org.junit.Test

class OverlaySetupVisibilityTest {
    @Test fun overlappingSetupOwnersAndRepeatedCloseCannotRestoreTooEarly() {
        val first = OverlaySetupVisibility.acquire()
        val second = OverlaySetupVisibility.acquire()
        try {
            assertTrue(OverlaySetupVisibility.active.value)
            first.close(); first.close()
            assertTrue(OverlaySetupVisibility.active.value)
            second.close()
            assertFalse(OverlaySetupVisibility.active.value)
            val later = OverlaySetupVisibility.acquire()
            try { first.close(); assertTrue(OverlaySetupVisibility.active.value) }
            finally { later.close() }
            assertFalse(OverlaySetupVisibility.active.value)
        } finally { first.close(); second.close() }
    }
}
