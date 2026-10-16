package com.sal7one.transiber.caption

import android.os.Build
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.UiLocaleProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No engine or provider calls; prove TV-only rendering has not removed phone controls. */
@RunWith(AndroidJUnit4::class)
class MobileOverlayControlsTest {
    @Test fun phoneStillHasWorkingBubbleSettings() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulators only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(!TvControls.isTvDevice(instrumentation.targetContext)) { "Phone emulator only" }
        val drawing = HardwareRendererCompat.isDrawingEnabled()
        HardwareRendererCompat.setDrawingEnabled(true)
        try {
            val config = MutableStateFlow(CaptionOverlayConfig(tvSubtitleMode = true))
            val state = MutableStateFlow(CaptionEngineController.State(
                status = CaptionEngineController.Status.RUNNING, history = listOf(CaptionLine("Phone caption fixture"))))
            val device = UiDevice.getInstance(instrumentation)
            var settings = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    settings = activity.getString(R.string.ui_caption_settings_da0c8)
                    activity.setContent {
                        UiLocaleProvider {
                            CaptionOverlayWindow(config, state, { config.value = it(config.value) },
                                onDrag = { _, _ -> }, onClose = {}, onClear = {}, availableHeightDp = 240f)
                        }
                    }
                }
                assertTrue(device.wait(Until.hasObject(By.desc(settings)), 10_000))
                assertTrue(device.hasObject(By.clazz("android.widget.Button")))
                device.findObject(By.desc(settings)).click()
                device.waitForIdle()
                assertTrue("Phone settings must still open", config.value.showSettings)
            }
        } finally { HardwareRendererCompat.setDrawingEnabled(drawing) }
    }
}
