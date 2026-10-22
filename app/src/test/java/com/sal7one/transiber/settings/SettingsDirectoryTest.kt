package com.sal7one.transiber.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsDirectoryTest {
    @Test fun benchmarksAndDownloadsAreDirectEntriesInBasicAndAdvancedMode() {
        listOf(false, true).forEach { advanced ->
            val entries = settingsDirectoryEntries(true, true, advanced)
            assertEquals(SettingsDirectoryEntry.BENCHMARK, entries.first())
            assertTrue(SettingsDirectoryEntry.DOWNLOADS in entries)
            assertEquals(entries.size, entries.distinct().size)
        }
    }

    @Test fun advancedOverlayControlsStayInAdvancedMode() {
        assertFalse(SettingsDirectoryEntry.OVERLAY in settingsDirectoryEntries(true, true, false))
        assertTrue(SettingsDirectoryEntry.OVERLAY in settingsDirectoryEntries(true, true, true))
    }

    @Test fun offlinePhoneKeepsBenchmarkAndLocalStagesWithoutCloudDependence() {
        val entries = settingsDirectoryEntries(false, true, false)
        assertTrue(entries.containsAll(listOf(SettingsDirectoryEntry.BENCHMARK, SettingsDirectoryEntry.DOWNLOADS,
            SettingsDirectoryEntry.BUNDLES, SettingsDirectoryEntry.SPEECH, SettingsDirectoryEntry.TRANSLATION,
            SettingsDirectoryEntry.VOICES, SettingsDirectoryEntry.CAMERA)))
    }

    @Test fun televisionsNeverAdvertiseLocalModelBenchmarksOrTransfers() {
        listOf(false, true).forEach { cloud ->
            listOf(false, true).forEach { advanced ->
                val entries = settingsDirectoryEntries(cloud, false, advanced)
                assertFalse(SettingsDirectoryEntry.BENCHMARK in entries)
                assertFalse(SettingsDirectoryEntry.DOWNLOADS in entries)
                assertFalse(SettingsDirectoryEntry.CAMERA in entries)
                assertTrue(SettingsDirectoryEntry.VOICES in entries) // Built-in system voices remain valid.
                assertEquals(cloud, SettingsDirectoryEntry.SPEECH in entries)
                assertEquals(cloud, SettingsDirectoryEntry.TRANSLATION in entries)
                assertEquals(cloud, SettingsDirectoryEntry.BUNDLES in entries)
            }
        }
    }

    @Test fun searchUsesLocalizedLabelsAndTaskTermsInsteadOfExactMenuNames() {
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.BENCHMARK, " GPU   speed ", "Benchmark models", ""))
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.BENCHMARK, "اختبار أَدَاء", "اختبار أداء النماذج", ""))
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.BENCHMARK, "بنشمارك", "Benchmark models", ""))
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.DOWNLOADS, "导入", "Downloads", ""))
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.TRANSLATION, "Arabic language", "Translation", ""))
        assertFalse(settingsSearchMatches(SettingsDirectoryEntry.BENCHMARK, "GPU unknownword", "Benchmark models", ""))
        assertTrue(settingsSearchMatches(SettingsDirectoryEntry.HELP, "", "Help", ""))
    }
}
