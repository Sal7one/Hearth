package com.sal7one.transiber.caption

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.i18n.UiLocaleProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual production overlay, with labeled fixture text. No speech/TTS engine is started. */
@RunWith(AndroidJUnit4::class)
class TvOverlayScreenshotTest {
    @Test fun captureTextOnlyTvOverlayAndErrors() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use a disposable TV emulator" }
        check(Build.VERSION.SDK_INT >= 34) { "Use an Android 14 or later TV emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(instrumentation.targetContext.filesDir, "tv-screenshots").apply { mkdirs() }
        output.listFiles()?.forEach { it.delete() }
        val config = MutableStateFlow(CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE, target = TranslationTarget.of("ar"),
            widthPercent = 80, bubbleHeightDp = 64, backgroundOpacity = 85,
            speakCaptions = true, speakerVolume = 70, speakerGender = SpeakerGender.FEMALE,
        ))
        val state = MutableStateFlow(CaptionEngineController.State(
            status = CaptionEngineController.Status.RUNNING, engineLabel = "UI demo",
            history = listOf(CaptionLine(
                "Welcome.",
                "مرحباً.",
            )),
        ))
        var overlay: ComposeView? = null
        var manager: WindowManager? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    check(TvControls.isTvDevice(activity)) { "Use an Android TV system image" }
                    activity.setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                                Color(0xFF122539), Color(0xFF251C39), Color(0xFF12101C),
                            ))).padding(48.dp), verticalArrangement = Arrangement.Center) {
                                Text("ANDROID TV · UI DEMO", color = Color(0xFFB7A0DF), fontSize = 16.sp)
                                Spacer(Modifier.height(24.dp))
                                Text("Hearth on TV", color = Color.White, fontSize = 42.sp)
                                Spacer(Modifier.height(16.dp))
                                Text("Captions. Translation. Read aloud.", color = Color(0xFFC7C5D0), fontSize = 20.sp)
                                Spacer(Modifier.height(48.dp))
                                Text("Sample text only\nNo recognition or voice synthesis running.",
                                    color = Color(0xFF9B97AC), fontSize = 13.sp)
                            }
                        }
                    }
                    val wm = activity.getSystemService(WindowManager::class.java)
                    manager = wm
                    val density = activity.resources.displayMetrics.density
                    val params = WindowManager.LayoutParams(
                        (activity.resources.displayMetrics.widthPixels * .8).toInt(),
                        (64 * density).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        // UiAutomator omits non-touchable windows from its interactive-window tree.
                        // Inspect the production renderer here; TvControlsTest covers the real flags.
                        TvControls.overlayWindowFlags(false, false, true) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv(), PixelFormat.TRANSLUCENT,
                    ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; x = 0; y = (24 * density).toInt() }
                    val view = ComposeView(activity).apply {
                        setViewTreeLifecycleOwner(activity)
                        setViewTreeSavedStateRegistryOwner(activity)
                        setContent {
                            MaterialTheme(colorScheme = darkColorScheme()) {
                                UiLocaleProvider {
                                    val current by config.collectAsState()
                                    CaptionOverlayWindow(config, state, onConfigChange = { transform ->
                                        config.value = transform(config.value).withUiClamp()
                                        params.flags = TvControls.overlayWindowFlags(false, config.value.showSettings, true) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                        wm.updateViewLayout(this@apply, params)
                                    }, onDrag = { _, _ -> }, onClose = {}, onClear = {}, availableHeightDp = current.bubbleHeightDp?.toFloat() ?: 64f)
                                }
                            }
                        }
                    }
                    overlay = view
                    wm.addView(view, params)
                }
                fun capture(name: String) {
                    device.waitForIdle()
                    Thread.sleep(700) // Allow the compositor to present the accessibility-ready UI.
                    assertTrue(device.takeScreenshot(File(output, "$name.png")))
                }
                capture("tv-initial-fixture")
                assertTrue(device.wait(Until.hasObject(By.textContains("Welcome.")), 15_000))
                fun assertTextOnly() {
                    device.waitForIdle()
                    assertFalse("TV must have no inline buttons", device.hasObject(By.clazz("android.widget.Button")))
                    assertFalse("TV must not show the translating/provider bar", device.hasObject(By.textContains("UI demo")))
                }
                assertTextOnly()
                capture("tv-subtitles-fixture")
                // Legacy flags cannot bring the phone sheet or recovery controls into the TV renderer.
                config.value = config.value.copy(tvSubtitleMode = false, showSettings = true, tapThrough = true)
                assertTextOnly()
                capture("tv-text-panel-fixture")
                scenario.onActivity {
                    val view = checkNotNull(overlay)
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.height = (32 * it.resources.displayMetrics.density).toInt()
                    checkNotNull(manager).updateViewLayout(view, params)
                }
                config.value = config.value.copy(tvSubtitleMode = true, bubbleHeightDp = 32)
                state.value = state.value.copy(status = CaptionEngineController.Status.ERROR, error = "Fixture speech failure")
                assertTrue(device.wait(Until.hasObject(By.text("Fixture speech failure")), 10_000))
                assertTextOnly()
                capture("tv-compact-error-fixture")
            } finally {
                scenario.onActivity {
                    overlay?.let { view -> manager?.removeViewImmediate(view); view.disposeComposition() }
                }
            }
        }
    }
}
