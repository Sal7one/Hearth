package com.sal7one.transiber.caption

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the real reveal callback without opening a window, capture source or native model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class MinimalCaptionOverlayControllerTest {
    private fun withController(action: suspend (Application, CaptionEngineController, CaptionOverlayController,
        MutableStateFlow<CaptionOverlayConfig>) -> Unit) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = CaptionEngineController(context, engineScope)
        // Existing provenance tests use the same boundary: no second-stage model is relevant here.
        ReflectionHelpers.getField<CoroutineScope>(engine, "translationScope").cancel()
        val overlay = CaptionOverlayController(context, engine)
        val live = ReflectionHelpers.getField<MutableStateFlow<CaptionOverlayConfig>>(overlay, "_config")
        try { action(context, engine, overlay, live) }
        finally {
            overlay.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            engine.shutdown()
            withTimeout(5_000) { engine.awaitReleased() }
            engineScope.cancel()
        }
    }

    @Test fun revealingAndCollapsingOnlyChangesLiveChromeAndLeavesSavedSettingsAndEngineUntouched() = withController { context, engine, overlay, live ->
        val saved = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64, bubbleHeightDp = 260,
            widthPercent = 80, anchor = CaptionAnchor.TOP, xOffsetPx = 34, yOffsetPx = 52,
            engine = CaptionEngineChoice.WHISPER, streamLanguage = "en", target = TranslationTarget.ARABIC,
            theme = CaptionTheme.LIGHT, backgroundOpacity = 55)
        CaptionConfigStore.update(context) { saved }
        val storedBefore = CaptionConfigStore.config(context).first()
        val engineBefore = storedBefore.copy(widthPercent = 65, minimalHeader = false, minimalHeightDp = 200)
        ReflectionHelpers.setField(engine, "currentConfig", engineBefore)
        val engineState = engine.state.value
        val session = storedBefore.copy(source = CaptionSource.MIC, paused = true,
            languagePicker = CaptionLanguagePicker.TARGET)
        live.value = session

        assertEquals(64, overlayHeightPx(1200, 1f, overlay.config.value))
        repeat(2) {
            overlay.setMinimalHeaderExpanded(true)
            assertEquals(session.copy(minimalHeaderExpanded = true), overlay.config.value)
            assertEquals(160, overlayHeightPx(1200, 1f, overlay.config.value))
            assertSame("A reveal must never reach the engine runtime update path", engineBefore, engine.currentConfig)
            assertSame(engineState, engine.state.value)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(storedBefore, CaptionConfigStore.config(context).first())

            overlay.setMinimalHeaderExpanded(false)
            assertEquals(session, overlay.config.value)
            assertEquals(64, overlayHeightPx(1200, 1f, overlay.config.value))
        }
        assertSame(engineBefore, engine.currentConfig)
        assertEquals(storedBefore, CaptionConfigStore.config(context).first())
        assertTrue(overlay.config.value.paused)
        assertEquals(CaptionSource.MIC, overlay.config.value.source)
        assertEquals(CaptionLanguagePicker.TARGET, overlay.config.value.languagePicker)
    }

    @Test fun tapThroughDisabledPresentationAndDestroyedOwnerCannotRevealChrome() = withController { _, engine, overlay, live ->
        val engineBefore = engine.currentConfig
        for (blocked in listOf(
            CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64, tapThrough = true),
            CaptionOverlayConfig(minimalHeader = false, minimalHeightDp = 64),
        )) {
            live.value = blocked
            overlay.setMinimalHeaderExpanded(true)
            assertEquals(blocked, overlay.config.value)
            assertFalse(overlay.config.value.minimalHeaderExpanded)
        }
        live.value = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64)
        overlay.destroy()
        val destroyed = overlay.config.value
        overlay.setMinimalHeaderExpanded(true)
        assertEquals(destroyed, overlay.config.value)
        assertSame(engineBefore, engine.currentConfig)
    }

    @Test fun ordinaryPreferenceMergesPreserveRevealedSessionStateButDisablingMinimalModeCollapsesIt() = withController { context, engine, overlay, live ->
        CaptionConfigStore.update(context) { CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 64, bubbleHeightDp = 240) }
        live.value = CaptionConfigStore.config(context).first().copy(source = CaptionSource.MIC, paused = true)
        overlay.setMinimalHeaderExpanded(true)
        val revealed = overlay.config.value
        CaptionConfigStore.update(context) { it.copy(theme = CaptionTheme.HIGH_CONTRAST, backgroundOpacity = 40) }
        live.value = CaptionConfigStore.config(context).first().withLiveOverlayState(revealed, CaptionSource.MIC)
        assertTrue(overlay.config.value.minimalHeaderExpanded && overlay.config.value.paused)
        assertEquals(CaptionSource.MIC, overlay.config.value.source)
        assertEquals(CaptionTheme.HIGH_CONTRAST, overlay.config.value.theme)
        assertEquals(40, overlay.config.value.backgroundOpacity)
        assertEquals(160, overlayHeightPx(1200, 1f, overlay.config.value))
        assertFalse(CaptionConfigStore.config(context).first().minimalHeaderExpanded)

        CaptionConfigStore.update(context) { it.copy(minimalHeader = false) }
        live.value = CaptionConfigStore.config(context).first().withLiveOverlayState(overlay.config.value, CaptionSource.MIC)
        overlay.setMinimalHeaderExpanded(true)
        assertFalse(overlay.config.value.minimalHeaderExpanded)
        assertFalse(overlay.config.value.minimalHeader)
        assertEquals(240, overlayHeightPx(1200, 1f, overlay.config.value))
        assertFalse(engine.currentConfig.minimalHeaderExpanded)
    }
}
