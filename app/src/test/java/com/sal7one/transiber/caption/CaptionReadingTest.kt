package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class CaptionReadingTest {
    private fun state() = CaptionEngineController.State(
        history = (1..130).map { CaptionLine(original = "line $it", id = it.toLong()) },
        partial = "next", partialTranslation = "التالي",
    )
    @Test fun nextPartialDoesNotEraseLastFinalWhenHistoryDisabled() {
        val snapshot = CaptionReading.snapshot(state(), true)
        assertEquals("line 130", CaptionReading.visibleFinals(snapshot, 0, false).single().original)
    }
    @Test fun holdKeepsBoundedSnapshotWhileLiveStateAdvances() {
        val snapshot = CaptionReading.snapshot(state(), true)
        val advanced = state().copy(history = emptyList(), partial = "new speech")
        assertEquals("next", snapshot.partial)
        assertEquals("new speech", advanced.partial)
        assertEquals(120, CaptionReading.visibleFinals(snapshot, 0, true).size)
        assertEquals("line 11", snapshot.history.first().original)
        assertEquals(5, CaptionReading.visibleFinals(snapshot, 4, false).size)
    }
    @Test fun finalOnlyReadingHidesBothInterimTexts() {
        val snapshot = CaptionReading.snapshot(state(), false)
        assertEquals("", snapshot.partial)
        assertNull(snapshot.partialTranslation)
        assertEquals(120, snapshot.history.size)
    }
    @Test fun newDefaultsKeepHistoryAndExplicitOptOutSurvivesPersistence() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        assertEquals(4, CaptionConfigStore.readFrom(prefs).historyLines)
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(historyLines = 0))
        assertEquals(0, CaptionConfigStore.readFrom(prefs).historyLines)
    }
}
