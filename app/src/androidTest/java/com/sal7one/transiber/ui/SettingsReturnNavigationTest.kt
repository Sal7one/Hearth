package com.sal7one.transiber.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.TvControls
import com.sal7one.transiber.preferences.UserPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.ceil
import java.util.Locale

/**
 * Real Activity/window/accessibility regression; no model, capture or provider request.
 * Compile on the host, execute only when the owner chooses a disposable phone emulator.
 * Tags are accessibility resource IDs, so this uses the existing UiAutomator dependency.
 */
@RunWith(AndroidJUnit4::class)
class SettingsReturnNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var previousDrawing: Boolean? = null
    private var previousLayout: String? = null
    private var previousLocales: LocaleListCompat? = null

    private enum class Origin(val page: Int?, val title: Int?) {
        HOME(null, null),
        CONVERSATION(7, R.string.label_homeservice_conversation_title),
        TRANSLATION(11, R.string.label_maintab_translate_title),
    }

    @Before fun prepareDisposablePhone() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Disposable emulators only; never run this navigation fixture on an owner device."
        }
        assertFalse("This fixture exercises the phone settings sheet", TvControls.isTvDevice(context))
        previousDrawing = HardwareRendererCompat.isDrawingEnabled()
        HardwareRendererCompat.setDrawingEnabled(true)
        val appearance = UserPreferences.store(context, "hearth-appearance")
        previousLayout = appearance.getString("navigation-layout", null)
        assertTrue(appearance.edit().putString("navigation-layout", "SIMPLE").commit())
        instrumentation.runOnMainSync {
            previousLocales = AppCompatDelegate.getApplicationLocales()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
    }

    @After fun restorePreferences() {
        if (previousDrawing == null) return
        val editor = UserPreferences.store(context, "hearth-appearance").edit()
        previousLayout?.let { editor.putString("navigation-layout", it) }
            ?: editor.remove("navigation-layout")
        assertTrue(editor.commit())
        previousLocales?.let { locales ->
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(locales) }
        }
        HardwareRendererCompat.setDrawingEnabled(requireNotNull(previousDrawing))
    }

    @Test fun systemBackReopensScrolledSettingsForEveryOrigin() {
        Origin.entries.forEach { exerciseSheetReturn(it, toolbarBack = false, recreate = false) }
    }

    @Test fun toolbarBackReopensScrolledSettingsForEveryOrigin() {
        Origin.entries.forEach { exerciseSheetReturn(it, toolbarBack = true, recreate = false) }
    }

    @Test fun recreationInChildKeepsSettingsReturnAndItsOriginalScreen() {
        Origin.entries.forEach { exerciseSheetReturn(it, toolbarBack = false, recreate = true) }
    }

    @Test fun fullSettingsPageKeepsItsScrollAfterChildAndRecreation() {
        ActivityScenario.launch<MainActivity>(launchIntent(3)).use { scenario ->
            val appearance = scrollPageTo(By.text(label(R.string.ui_appearance_navigation_433af)))
            val before = stableBounds(By.text(label(R.string.ui_appearance_navigation_433af)))
            appearance.click()
            await(By.res("app-back"))
            scenario.recreate()
            await(By.res("app-back")).click()
            await(By.text(label(R.string.label_maintab_settings_title)))
            assertFalse("Full settings must return as a page", device.hasObject(By.res("quick-settings-sheet")))
            val after = stableBounds(By.text(label(R.string.ui_appearance_navigation_433af)))
            assertClose("Full settings link returned at its previous scroll position", before.centerY(), after.centerY())
        }
    }

    private fun exerciseSheetReturn(origin: Origin, toolbarBack: Boolean, recreate: Boolean) {
        ActivityScenario.launch<MainActivity>(launchIntent(origin.page)).use { scenario ->
            assertOrigin(origin)
            await(By.res("quick-settings-open")).click()
            await(By.res("quick-settings-sheet"))
            // Wait for saved pipeline data before positioning the anchor below its cards.
            await(By.text(label(R.string.settings_flow_speech)))
            val anchor = scrollSheetToAppearance()
            val sheetBefore = stableBounds(By.res("quick-settings-sheet"))
            val anchorBefore = stableBounds(By.res("quick-settings-appearance"))
            assertFalse("Exercise a nonzero sheet scroll, not only reopening at the top",
                visibleInside(By.text(label(R.string.settings_flow_pipeline)), sheetBefore))
            anchor.click()
            await(By.text(label(R.string.ui_appearance_navigation_433af)))
            assertFalse("Child must replace the settings sheet", device.hasObject(By.res("quick-settings-sheet")))
            if (recreate) {
                scenario.recreate()
                await(By.text(label(R.string.ui_appearance_navigation_433af)))
                assertFalse(device.hasObject(By.res("quick-settings-sheet")))
            }
            if (toolbarBack) await(By.res("app-back")).click() else device.pressBack()
            val sheetAfter = stableBounds(By.res("quick-settings-sheet"))
            val anchorAfter = stableBounds(By.res("quick-settings-appearance"))
            assertClose("${origin.name}: Back restores the visible settings anchor",
                anchorBefore.centerY() - sheetBefore.top, anchorAfter.centerY() - sheetAfter.top)
            assertFalse("Back must retain the scrolled sheet",
                visibleInside(By.text(label(R.string.settings_flow_pipeline)), sheetAfter))
            val close = englishContext().getString(R.string.ui_close_1_s_93864, label(R.string.ui_settings_c7f73))
            await(By.desc(close)).click()
            assertTrue(device.wait(Until.gone(By.res("quick-settings-sheet")), TIMEOUT))
            assertOrigin(origin)
        }
    }

    private fun scrollSheetToAppearance(): UiObject2 {
        repeat(8) {
            val sheet = await(By.res("quick-settings-sheet"))
            val bounds = sheet.visibleBounds
            val target = device.findObject(By.res("quick-settings-appearance"))
            if (target != null && bounds.contains(target.visibleBounds) &&
                !visibleInside(By.text(label(R.string.settings_flow_pipeline)), bounds)) return target
            sheet.scroll(Direction.DOWN, .65f)
            device.waitForIdle()
        }
        error("Could not scroll settings to its Appearance action in this phone viewport")
    }

    private fun scrollPageTo(selector: BySelector): UiObject2 {
        repeat(8) {
            val target = device.findObject(selector)
            val viewport = Rect(0, 0, device.displayWidth, device.displayHeight)
            if (target != null && target.visibleBounds.height() > 0 &&
                !visibleInside(By.text(label(R.string.settings_flow_pipeline)), viewport)) return target
            val scroll = device.findObjects(By.scrollable(true)).lastOrNull()
                ?: error("Settings page has no scroll container")
            scroll.scroll(Direction.DOWN, .65f)
            device.waitForIdle()
        }
        error("Settings page action is unavailable: $selector")
    }

    private fun assertOrigin(origin: Origin) {
        await(By.text(origin.title?.let(::label) ?: "Hearth"))
        await(By.res("quick-settings-open"))
        assertFalse("Child settings page must not remain visible", device.hasObject(By.res("app-back")) && origin == Origin.HOME)
    }

    private fun launchIntent(page: Int?) = Intent(context, MainActivity::class.java).apply {
        if (page != null) putExtra("page", page)
    }

    private fun englishContext() = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    private fun label(id: Int) = englishContext().getString(id)
    private fun await(selector: BySelector): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) {
        "Missing navigation UI: $selector"
    }

    private fun visibleInside(selector: BySelector, viewport: Rect): Boolean {
        val bounds = device.findObject(selector)?.visibleBounds ?: return false
        return bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(viewport, bounds)
    }

    private fun stableBounds(selector: BySelector): Rect {
        var previous = Rect(await(selector).visibleBounds)
        val deadline = SystemClock.uptimeMillis() + 3_000
        var matches = 0
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            val current = Rect(await(selector).visibleBounds)
            if (current == previous && current.height() > 0) {
                if (++matches >= 3) return current
            } else matches = 0
            previous = current
        }
        error("Navigation anchor did not settle: $selector")
    }

    private fun assertClose(message: String, before: Int, after: Int) {
        val tolerance = ceil(12 * context.resources.displayMetrics.density).toInt()
        assertTrue("$message (before=$before, after=$after)", abs(before - after) <= tolerance)
    }

    private companion object { const val TIMEOUT = 15_000L }
}
