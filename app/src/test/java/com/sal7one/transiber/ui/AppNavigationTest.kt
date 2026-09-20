package com.sal7one.transiber.ui

import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun setupReturnsToItsOriginAndTabsKeepIndependentPaths() {
        var nav = AppNavigation.initial(11).open(12).open(2)
        nav = nav.select(MainTab.SETTINGS).open(1)
        assertEquals(MainTab.SETTINGS, nav.tab)
        nav = nav.select(MainTab.TRANSLATE)
        assertEquals(2, nav.page)
        assertEquals(12, nav.back().page)
        assertEquals(11, nav.back().back().page)
        assertEquals(1, nav.select(MainTab.SETTINGS).page)
    }

    @Test fun reselectionReturnsToTabRootAndBackFromRootReturnsToCaptions() {
        val nav = AppNavigation.initial(7).open(1).select(MainTab.TALK)
        assertEquals(7, nav.page)
        assertTrue(nav.isRoot)
        assertEquals(0, nav.back().page)
        assertFalse(nav.back().canGoBack)
    }

    @Test fun oldOverlayAndExternalLinksOpenCorrectPageWithSettingsReturn() {
        for (page in 0..14) {
            val nav = AppNavigation.initial(page)
            assertEquals(page, nav.page)
            if (MainTab.entries.none { it.page == page }) {
                assertEquals(MainTab.SETTINGS, nav.tab)
                assertEquals(3, nav.back().page)
            }
        }
    }

    @Test fun repeatedModelDownloadLinksDoNotAccumulateBackStack() {
        var nav = AppNavigation.initial(10)
        repeat(100) { nav = nav.open(1).open(2) }
        assertEquals(1, nav.back().page)
        assertEquals(10, nav.back().back().page)
    }

    @Test fun processRestorationRetainsEveryTabPath() {
        val nav = AppNavigation.initial(0).open(5).open(1)
            .select(MainTab.TRANSLATE).open(12).select(MainTab.CAMERA).open(2)
        val restored = AppNavigation.restore(nav.save())
        assertEquals(nav.save(), restored.save())
        assertEquals(12, restored.select(MainTab.TRANSLATE).page)
        assertEquals(5, restored.select(MainTab.CAPTIONS).back().page)
    }

    @Test fun appearanceAndShortcutsReturnToTheFeatureThatOpenedThem() {
        val nav = AppNavigation.initial(11).open(13).open(14)
        assertEquals(13, nav.back().page)
        assertEquals(11, nav.back().back().page)
        assertEquals(nav.save(), AppNavigation.restore(nav.save()).save())
    }

    @Test fun malformedSavedStateCannotOpenAnInvalidPage() {
        for (saved in listOf(emptyList(), listOf(-1), listOf(1, 999), listOf(0, 1, 99))) {
            assertEquals(AppNavigation.initial().save(), AppNavigation.restore(saved).save())
        }
        assertThrows(IllegalArgumentException::class.java) { AppNavigation.initial().open(99) }
    }
}
