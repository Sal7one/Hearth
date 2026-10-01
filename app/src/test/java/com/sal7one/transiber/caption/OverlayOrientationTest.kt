package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class OverlayOrientationTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun repeatedRotationNotificationsEmitWithoutASettingsWrite() = runTest {
        val events = mutableListOf<Long>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { CaptionConfigStore.rotation.take(3).toList(events) }
        CaptionConfigStore.onOrientationChanged(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        CaptionConfigStore.onOrientationChanged(android.content.res.Configuration.ORIENTATION_PORTRAIT)
        collector.join()
        assertEquals(listOf(events.first(), events.first() + 1, events.first() + 2), events)
    }

    @Test fun rotationSwapsGeometryWhilePreservingPauseAndOpenControls() {
        val live = CaptionOverlayConfig(paused = true, showSettings = true, tapThrough = true,
            speakCaptions = true, speakerVolume = 25, xOffsetPx = 99)
        val landscape = CaptionConfigStore.readFrom(prefs(), OverlayOrientation.LANDSCAPE)
        val rotated = live.withStoredGeometry(landscape)
        assertEquals(40, rotated.widthPercent)
        assertEquals(60, rotated.maxHeightPercent)
        assertEquals(0, rotated.xOffsetPx)
        assertTrue(rotated.paused && rotated.showSettings && rotated.tapThrough && rotated.speakCaptions)
        assertEquals(25, rotated.speakerVolume)
    }

    @Test fun savedReadAloudChangesApplyWithoutResettingSessionControls() {
        val live = CaptionOverlayConfig(source = CaptionSource.MIC, paused = true, showSettings = true,
            tapThrough = true, speakCaptions = true)
        val stored = CaptionOverlayConfig(source = CaptionSource.PLAYBACK_CAPTURE, speakCaptions = false,
            speakerGender = SpeakerGender.FEMALE, speakerVolume = 30)
        val next = stored.withLiveOverlayState(live, CaptionSource.MIC)
        assertFalse(next.speakCaptions)
        assertEquals(30, next.speakerVolume)
        assertEquals(SpeakerGender.FEMALE, next.speakerGender)
        assertEquals(CaptionSource.MIC, next.source)
        assertTrue(next.paused && next.showSettings && next.tapThrough)
    }

    private fun prefs(vararg pairs: Pair<String, Any>) = mutablePreferencesOf(
        *pairs.map { (k, v) ->
            when (v) {
                is Int -> intPreferencesKey(k) to v
                is String -> stringPreferencesKey(k) to v
                else -> error("unsupported")
            }
        }.toTypedArray())

    @Test fun freshInstallsGetOrientationSpecificGeometryDefaults() {
        val portrait = CaptionConfigStore.readFrom(prefs(), OverlayOrientation.PORTRAIT)
        val landscape = CaptionConfigStore.readFrom(prefs(), OverlayOrientation.LANDSCAPE)
        assertEquals(60, portrait.widthPercent)
        assertEquals(40, portrait.maxHeightPercent)
        assertEquals("landscape defaults differ from portrait", 40, landscape.widthPercent)
        assertEquals(60, landscape.maxHeightPercent)
        assertEquals(CaptionAnchor.BOTTOM, portrait.anchor)
    }

    @Test fun legacyStoredGeometrySeedsOnlyPortrait() {
        val legacy = prefs("width_percent" to 90, "max_height_percent_v3" to 55, "anchor" to "TOP",
            "x_offset_px" to 30, "y_offset_px" to 20)
        val portrait = CaptionConfigStore.readFrom(legacy, OverlayOrientation.PORTRAIT)
        val landscape = CaptionConfigStore.readFrom(legacy, OverlayOrientation.LANDSCAPE)
        assertEquals("user's tuned portrait bubble survives the upgrade", 90, portrait.widthPercent)
        assertEquals(55, portrait.maxHeightPercent)
        assertEquals(CaptionAnchor.TOP, portrait.anchor)
        assertEquals(30, portrait.xOffsetPx)
        assertEquals("landscape ignores portrait-only legacy values", 0, landscape.xOffsetPx)
        assertEquals(40, landscape.widthPercent)
    }

    @Test fun writesLandsInTheActiveOrientationOnlyAndBothAreRemembered() {
        val shared = prefs()
        val tuned = CaptionConfigStore.readFrom(shared, OverlayOrientation.LANDSCAPE)
            .copy(widthPercent = 75, maxHeightPercent = 35, xOffsetPx = 12)
        CaptionConfigStore.writeInto(shared, tuned, OverlayOrientation.LANDSCAPE)

        val landscape = CaptionConfigStore.readFrom(shared, OverlayOrientation.LANDSCAPE)
        val portrait = CaptionConfigStore.readFrom(shared, OverlayOrientation.PORTRAIT)
        assertEquals(75, landscape.widthPercent)
        assertEquals(35, landscape.maxHeightPercent)
        assertEquals(12, landscape.xOffsetPx)
        assertEquals("portrait untouched by landscape edits", 60, portrait.widthPercent)
        assertEquals(0, portrait.xOffsetPx)

        val portraitTuned = portrait.copy(widthPercent = 88)
        CaptionConfigStore.writeInto(shared, portraitTuned, OverlayOrientation.PORTRAIT)
        assertEquals(88, CaptionConfigStore.readFrom(shared, OverlayOrientation.PORTRAIT).widthPercent)
        assertEquals("landscape keeps its own value after a portrait edit", 75,
            CaptionConfigStore.readFrom(shared, OverlayOrientation.LANDSCAPE).widthPercent)
    }

    @Test fun nonGeometryOptionsStaySharedAcrossOrientations() {
        val shared = prefs()
        val changed = CaptionConfigStore.readFrom(shared, OverlayOrientation.LANDSCAPE)
            .copy(theme = CaptionTheme.LIGHT, widthPercent = 50)
        CaptionConfigStore.writeInto(shared, changed, OverlayOrientation.LANDSCAPE)
        assertEquals(CaptionTheme.LIGHT, CaptionConfigStore.readFrom(shared, OverlayOrientation.PORTRAIT).theme)
        assertEquals("portrait width still its own", 60, CaptionConfigStore.readFrom(shared, OverlayOrientation.PORTRAIT).widthPercent)
    }

    @Test fun clampAcceptsTheLandscapeDefaultWidth() {
        val clamped = CaptionConfigStore.readFrom(prefs(), OverlayOrientation.LANDSCAPE).withUiClamp()
        assertEquals(40, clamped.widthPercent)
        assertEquals(49, 49.coerceIn(40, 100))
    }
}
