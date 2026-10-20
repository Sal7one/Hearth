package com.sal7one.transiber.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsSheetReturnTest {
    @Test fun everySettingsEntryReturnsToItsHomeSheetInsteadOfTreatingThePageAsHome() {
        // Home can hide a previously visited page; restoring it must keep Home visible.
        val origin = AppNavigation.initial(12)
        for (page in listOf(1, 2, 3, 4, 5, 8, 9, 12, 13, 14, 15, 16)) {
            val restored = roundTrip(listOf(SettingsSheetReturn(origin, true, null, page))).single()
            assertTrue(restored.reachedFrom(AppNavigation.initial(page)))
            assertTrue(restored.home)
            assertNull(restored.homeDestination)
            assertEquals(origin.save(), restored.origin.save())
        }
    }

    @Test fun featureOriginsAndTheirExistingTabPathsArePreserved() {
        for (tab in MainTab.entries) {
            val origin = AppNavigation.initial(0).open(5).select(tab).open(12)
            val restored = roundTrip(listOf(SettingsSheetReturn(origin, false, tab.page, 13))).single()
            assertFalse(restored.home)
            assertEquals(tab.page, restored.homeDestination)
            assertEquals(origin.save(), restored.origin.save())
            assertEquals(tab, restored.origin.tab)
        }
    }

    @Test fun modelAndDownloadChildrenUnwindBeforeReturningToTheSheet() {
        val origin = AppNavigation.initial(7)
        val entry = origin.open(4)
        val frame = SettingsSheetReturn(origin, false, 7, entry.page)
        val downloads = entry.open(1).open(2)
        assertFalse(frame.reachedFrom(downloads))
        assertFalse(frame.reachedFrom(downloads.back()))
        assertTrue(frame.reachedFrom(downloads.back().back()))
    }

    @Test fun crossTabDestinationsCannotStrandTheSuspendedSheet() {
        val frame = SettingsSheetReturn(AppNavigation.initial(11), false, 11, 4)
        val changedTab = AppNavigation.initial(4).open(3)
        assertFalse(changedTab.containsInCurrentPath(4))
        assertTrue(frame.reachedFrom(changedTab))
    }

    @Test fun selectingTheCurrentSettingsPageStillHasASheetReturn() {
        val current = AppNavigation.initial(12)
        val frame = SettingsSheetReturn(current, false, 12, 12)
        assertTrue(frame.reachedFrom(current))
        assertEquals(current.save(), frame.origin.save())
    }

    @Test fun nestedSettingsSheetsReturnToTheirOwnOriginsInOrderAfterRecreation() {
        val home = AppNavigation.initial()
        val speech = AppNavigation.initial(4)
        var history = SettingsSheetReturn.push(emptyList(), SettingsSheetReturn(home, true, null, 4))
        history = SettingsSheetReturn.push(history, SettingsSheetReturn(speech, false, 4, 13))
        history = roundTrip(history)
        val appearance = speech.open(13)
        assertTrue(history.last().reachedFrom(appearance))
        assertEquals(speech.save(), history.last().origin.save())
        history = history.dropLast(1)
        assertTrue(history.last().reachedFrom(speech))
        assertTrue(history.last().home)
        assertEquals(home.save(), history.last().origin.save())
    }

    @Test fun anUnrelatedNestedPageDoesNotTriggerAnEarlyReturn() {
        val origin = AppNavigation.initial(3)
        val frame = SettingsSheetReturn(origin, false, 3, 9)
        assertFalse(frame.reachedFrom(origin.open(9).open(1).open(12)))
    }

    @Test fun savedOriginsRetainAllIndependentTabStacks() {
        val origin = AppNavigation.initial(0).open(4).open(16)
            .select(MainTab.TRANSLATE).open(12).select(MainTab.CAMERA).open(1).open(2)
        val frame = roundTrip(listOf(SettingsSheetReturn(origin, false, 10, 13))).single()
        assertEquals(origin.save(), frame.origin.save())
        assertEquals(16, frame.origin.select(MainTab.CAPTIONS).page)
        assertEquals(12, frame.origin.select(MainTab.TRANSLATE).page)
    }

    @Test fun excessiveNestedGearNavigationKeepsABoundedRestorableRecentHistory() {
        var history = emptyList<SettingsSheetReturn>()
        repeat(100) { index ->
            history = SettingsSheetReturn.push(history, SettingsSheetReturn(AppNavigation.initial(index % 17), false, null, 13))
        }
        assertEquals(32, history.size)
        assertEquals(SettingsSheetReturn.saveHistory(history), SettingsSheetReturn.saveHistory(roundTrip(history)))
    }

    @Test fun malformedOrTruncatedSavedHistoryCannotRestoreAnUnexpectedDestination() {
        val valid = SettingsSheetReturn.saveHistory(listOf(SettingsSheetReturn(AppNavigation.initial(11), false, 11, 13)))
        val invalid = listOf(emptyList(), listOf(-1), listOf(33), valid.dropLast(1), valid + 7,
            valid.toMutableList().apply { this[1] = Int.MAX_VALUE },
            valid.toMutableList().apply { this[2] = 99 },
            valid.toMutableList().apply { this[3] = 2 },
            valid.toMutableList().apply { this[4] = 99 },
            valid.toMutableList().apply { this[5] = 99 })
        for (saved in invalid) assertTrue(SettingsSheetReturn.restoreHistory(saved).isEmpty())
        assertTrue(SettingsSheetReturn.restoreHistory(listOf(0)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SettingsSheetReturn(AppNavigation.initial(), false, null, 99) }
    }

    private fun roundTrip(history: List<SettingsSheetReturn>) =
        SettingsSheetReturn.restoreHistory(SettingsSheetReturn.saveHistory(history))
}
