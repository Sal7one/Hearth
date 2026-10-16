package com.sal7one.transiber.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ApiKeyStore
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.setup.EasySetupActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import androidx.test.platform.graphics.HardwareRendererCompat
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** UI-only fixtures: disposable emulators, fake credential, no capture or provider requests. */
@RunWith(AndroidJUnit4::class)
class MobileTvLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var previousDrawing: Boolean? = null
    @After fun restoreDrawing() { previousDrawing?.let { HardwareRendererCompat.setDrawingEnabled(it) } }
    private fun guard(tv: Boolean) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulators only; never run on the owner TV." }
        assertEquals(tv, TvControls.isTvDevice(context))
        // ATD disables drawing before any window exists. Enable it before Activity launch,
        // rather than forcing a redraw of stopped windows during each screenshot.
        previousDrawing = HardwareRendererCompat.isDrawingEnabled()
        HardwareRendererCompat.setDrawingEnabled(true)
    }
    private fun label(id: Int, locale: String = "en"): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }).getString(id)
    private fun find(id: Int, locale: String = "en"): UiObject2 = checkNotNull(device.wait(Until.findObject(By.text(label(id, locale))), 15_000)) {
        capture("missing-$id")
        device.dumpWindowHierarchy(File(context.filesDir, "layout-review/missing-$id.xml"))
        "Missing UI label: ${label(id, locale)}"
    }
    private fun waitOrientation(portrait: Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while ((device.displayHeight > device.displayWidth) != portrait && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertEquals("Display orientation", portrait, device.displayHeight > device.displayWidth)
        device.waitForIdle()
    }
    private fun capture(name: String) {
        device.waitForIdle()
        val root = File(context.filesDir, "layout-review").apply { mkdirs() }
        instrumentation.waitForIdleSync()
        Thread.sleep(200) // Present the compositor frame after orientation/theme changes.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val colors = mutableSetOf<Int>()
        for (x in 0 until bitmap.width step 32) for (y in 0 until bitmap.height step 32) colors += bitmap.getPixel(x, y)
        assertTrue("Screenshot did not render UI: $name", colors.size > 16)
        File(root, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun tvBeforeAfterSetupAndFullInterfaceRemainSeparate() {
        guard(tv = true)
        ApiKeyStore.setOpenAiKey(context, "")
        runBlocking { CaptionConfigStore.update(context) { it.resetOverlayPresentation(OverlayOrientation.LANDSCAPE) } }
        context.getSharedPreferences("hearth-appearance", 0).edit().putString("navigation-layout", "TABS").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            find(R.string.tv_start_hearth)
            find(R.string.overlay_stop_launcher_title)
            assertFalse(device.hasObject(By.text(label(R.string.tv_stt_action))))
            capture("tv-welcome-en")
            find(R.string.tv_start_hearth).click()
            find(R.string.tv_pair_title)
            find(R.string.tv_tab_language)
            find(R.string.tv_tab_voice)
            capture("tv-setup-en")
            runBlocking { EasySetupActions(context).applyTvOpenAi("unit-test-emulator-layout-key") }
            find(R.string.tv_done).click()
            find(R.string.tv_setup_saved_status)
            assertFalse(device.hasObject(By.text(label(R.string.tv_stt_action))))
            capture("tv-ready-en")
            context.getSharedPreferences("hearth-appearance", 0).edit().putString("theme-mode", "LIGHT").commit()
            capture("tv-ready-light")
            context.getSharedPreferences("hearth-appearance", 0).edit().putString("theme-mode", "DARK").commit()
            capture("tv-ready-dark")
            find(R.string.tv_manage_setup).click()
            find(R.string.tv_change_key_phone)
            find(R.string.tv_advanced_settings).click()
            find(R.string.label_maintab_captions_title)
            find(R.string.tv_mode).click()
            find(R.string.tv_start_hearth)
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
            find(R.string.tv_start_hearth, "ar")
            capture("tv-ready-ar")
            find(R.string.tv_overlay_editor, "ar").click()
            find(R.string.overlay_controls_title, "ar")
            capture("tv-editor-ar")
            device.pressBack()
            find(R.string.tv_start_hearth, "ar")
        }
        ApiKeyStore.setOpenAiKey(context, "")
    }

    @Test fun mobilePortraitAndLandscapeNeverBecomeTvDashboard() {
        guard(tv = false)
        device.setOrientationNatural()
        waitOrientation(portrait = true)
        context.getSharedPreferences("hearth-appearance", 0).edit().putString("navigation-layout", "SIMPLE").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            device.waitForIdle()
            assertFalse(device.hasObject(By.text(label(R.string.tv_start_hearth))))
            waitOrientation(portrait = true)
            capture("mobile-home-portrait")
            device.setOrientationLeft()
            waitOrientation(portrait = false)
            assertFalse(device.hasObject(By.text(label(R.string.tv_start_hearth))))
            capture("mobile-home-landscape")
            context.getSharedPreferences("hearth-appearance", 0).edit().putString("navigation-layout", "TABS").commit()
            device.waitForIdle()
            assertFalse(device.hasObject(By.text(label(R.string.tv_start_hearth))))
            capture("mobile-tabs-landscape")
            context.startActivity(Intent(context, OverlayControlsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            find(R.string.overlay_controls_title)
            capture("mobile-editor-landscape")
            device.setOrientationNatural()
            waitOrientation(portrait = true)
            find(R.string.overlay_controls_title)
            capture("mobile-editor-portrait")
            device.pressBack()
        }
        device.unfreezeRotation()
    }
}
