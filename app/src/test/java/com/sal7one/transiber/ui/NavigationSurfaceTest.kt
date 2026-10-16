package com.sal7one.transiber.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationSurfaceTest {
    @Test fun phonesKeepTheirInterfaceAndTvCanSwitchToTheFullInterface() {
        assertFalse(useTvInterface(television = false, fullInterface = false))
        assertFalse(useTvInterface(television = false, fullInterface = true))
        assertTrue(useTvInterface(television = true, fullInterface = false))
        assertFalse(useTvInterface(television = true, fullInterface = true))
    }

    @Test fun tvSetupAndDashboardCannotRestoreMobileScreenState() {
        assertEquals("tv-home", navigationStateKey(true, true, 15))
        assertEquals("tv-home", navigationStateKey(true, false, 0))
        assertEquals("tv-setup", navigationStateKey(true, false, 15))
        assertEquals("simple-home", navigationStateKey(false, true, 15))
        for (page in 0..16) {
            assertEquals(page, navigationStateKey(false, false, page))
            assertNotEquals(navigationStateKey(false, false, page), navigationStateKey(true, false, page))
        }
    }
}
