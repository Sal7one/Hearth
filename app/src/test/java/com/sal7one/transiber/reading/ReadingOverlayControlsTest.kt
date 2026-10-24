package com.sal7one.transiber.reading

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sal7one.transiber.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingOverlayControlsTest {
    @Test fun ignoredAreaControlsStayExplicitAndRenderDoesNotStartCapturesOrResetThePolicy() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var captured = 0; var excluded = 0; var cleared = 0
        val controls = ReadingOverlayControls(owner.get(), callbacks(translate = { captured++ }).copy(
            excludeArea = { excluded++ }, clearExclusions = { cleared++ }))
        owner.get().setContentView(controls)
        controls.render(ReadingOverlayUiState(mode = "manual", excludedAreas = 0))
        val add = button(controls, R.string.reading_exclusions_add)
        val clear = button(controls, R.string.reading_exclusions_clear)
        assertTrue(add.isEnabled); assertFalse(clear.isEnabled)
        add.performClick()
        assertEquals(1, excluded); assertEquals(0, captured)
        controls.render(ReadingOverlayUiState(working = true, excludedAreas = 1))
        assertTrue("A busy moving page must still let its owner draw a repair exclusion", add.isEnabled)
        controls.render(ReadingOverlayUiState(mode = "page", excludedAreas = 4, inactiveExcludedAreas = 1))
        assertFalse(add.isEnabled); assertTrue(clear.isEnabled)
        assertTrue(descendants(controls).filterIsInstance<android.widget.TextView>()
            .any { it.text == owner.get().getString(R.string.reading_exclusions_inactive, 4, 1) })
        assertEquals(1, excluded); assertEquals(0, cleared); assertEquals(0, captured)
        clear.performClick()
        assertEquals(1, cleared); assertEquals(0, captured)
        controls.render(ReadingOverlayUiState(excludedAreas = 0))
        assertTrue(add.isEnabled); assertFalse(clear.isEnabled)
        owner.pause().stop().destroy()
    }

    @Test @Config(sdk = [33]) fun serviceOwnedOverlayOptsIntoTheAndroidThirteenBackDispatcherAtApplicationLevel() {
        // The platform checks ApplicationInfo for a Service's overlay window; opting in only
        // on ReadingStartActivity leaves its real window callback silently unregistered.
        val application = RuntimeEnvironment.getApplication()
        // Read the compiled, merged manifest directly. Robolectric does not populate the
        // platform's hidden ApplicationInfo.privateFlagsExt bit for this attribute.
        val manifest = application.assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            var applicationFound = false
            while (manifest.eventType != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                    applicationFound = true
                    assertTrue("The merged manifest must enable Back callbacks for service-owned windows",
                        manifest.getAttributeBooleanValue("http://schemas.android.com/apk/res/android",
                            "enableOnBackInvokedCallback", false))
                    break
                }
                manifest.next()
            }
            assertTrue("The test APK must contain its merged application manifest", applicationFound)
        } finally { manifest.close() }
    }

    @Test fun manualModeSelectionDoesNotTranslateAndTheVisibleRefreshAcceptsTouch() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var translated = 0
        val chosenModes = mutableListOf<String>()
        val controls = ReadingOverlayControls(owner.get(), callbacks(translate = { translated++ }).copy(
            mode = { chosenModes += it }))
        owner.get().setContentView(controls)
        button(controls, R.string.reading_overlay_automation).performClick()
        button(controls, R.string.reading_overlay_on_tap).performClick()
        assertEquals(listOf("manual"), chosenModes)
        assertEquals("Choosing a refresh policy must not start a capture", 0, translated)
        controls.render(ReadingOverlayUiState(mode = "manual"))
        measure(controls, 360, 620)
        tap(button(controls, R.string.reading_overlay_translate))
        assertEquals(1, translated)
        controls.render(ReadingOverlayUiState(working = true))
        tap(button(controls, R.string.reading_overlay_reading))
        assertEquals("Busy refresh must not enqueue a second capture", 1, translated)
        controls.render(ReadingOverlayUiState(error = "Translator unavailable"))
        assertEquals("Translator unavailable", controls.statusText.text.toString())
        tap(button(controls, R.string.reading_overlay_translate))
        assertEquals("A failed capture must leave manual retry enabled", 2, translated)
        controls.render(ReadingOverlayUiState(working = true))
        assertEquals(owner.get().getString(R.string.reading_overlay_reading), controls.statusText.text.toString())
        controls.statusText.text = "Page translated"
        controls.render(ReadingOverlayUiState())
        assertEquals("Progress render must retain the service's completed-page status", "Page translated", controls.statusText.text.toString())
        owner.pause().stop().destroy()
    }

    @Test fun compactRefreshRetriesAnErrorWithoutOpeningOptions() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var translated = 0
        var opened = 0
        val handle = ReadingOverlayHandle(owner.get(), { translated++ }, { opened++ })
        owner.get().setContentView(handle)
        handle.render(ReadingOverlayUiState(error = "Translation model is missing"))
        measure(handle, 144, 48)
        val retry = descendants(handle).filterIsInstance<Button>().single { it.isShown && it.text == "!" }
        assertTrue(retry.contentDescription.contains("Translation model is missing"))
        assertTrue(retry.contentDescription.contains("retry"))
        tap(retry)
        assertEquals(1, translated)
        assertEquals(0, opened)
        handle.render(ReadingOverlayUiState(working = true))
        tap(retry)
        assertEquals(1, translated)
        handle.render(ReadingOverlayUiState())
        assertEquals("↻", retry.text.toString())
        assertFalse(retry.contentDescription.contains("missing"))
        tap(retry)
        assertEquals(2, translated)
        assertEquals(0, opened)
        owner.pause().stop().destroy()
    }

    @Test fun stopActionKeepsScrollClearanceAboveGestureAndNavigationInsets() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var stopped = 0
        val controls = ReadingOverlayControls(owner.get(), callbacks().copy(stop = { stopped++ }))
        owner.get().setContentView(controls)
        val bars = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, dp(controls, 48)))
            .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, dp(controls, 24)))
            .build()
        ViewCompat.dispatchApplyWindowInsets(controls, bars)
        assertEquals(dp(controls, 60), controls.paddingBottom)
        button(controls, R.string.reading_overlay_more).performClick()
        measure(controls, 296, 440)
        val scroll = descendants(controls).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.paddingBottom >= dp(controls, 24))
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val stop = button(controls, R.string.reading_overlay_stop)
        val stopBounds = Rect()
        val scrollBounds = Rect()
        assertTrue(stop.getGlobalVisibleRect(stopBounds))
        assertTrue(scroll.getGlobalVisibleRect(scrollBounds))
        assertEquals("Stop must be a full-width action", (stop.parent as View).width, stop.width)
        assertTrue("The final action needs breathing space before the sheet bottom",
            scrollBounds.bottom - stopBounds.bottom >= dp(controls, 24))
        tap(stop)
        assertEquals(1, stopped)
        ViewCompat.dispatchApplyWindowInsets(controls, WindowInsetsCompat.Builder().build())
        assertEquals("Insets must not accumulate or survive hiding the navigation bar", dp(controls, 12), controls.paddingBottom)
        owner.pause().stop().destroy()
    }

    @Test fun backClosesTheControlWindowOnceWithoutTriggeringTranslation() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        var dismissed = 0
        var translated = 0
        val controls = ReadingOverlayControls(owner.get(), callbacks(
            dismiss = { dismissed++ }, translate = { translated++ }))
        owner.get().setContentView(controls)
        assertTrue(controls.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertEquals(0, dismissed)
        assertTrue(controls.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(1, dismissed)
        assertEquals(0, translated)
        assertTrue(controls.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(controls.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(2, dismissed)
        val cancelled = KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), KeyEvent.FLAG_CANCELED)
        assertTrue(controls.dispatchKeyEvent(cancelled))
        assertEquals(2, dismissed)
        val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try { assertTrue(controls.onTouchEvent(outside)) } finally { outside.recycle() }
        assertEquals(3, dismissed)
        assertEquals(0, translated)
        owner.pause().stop().destroy()
    }

    @Test @Config(sdk = [28]) fun androidNineConsumesBackBeforeTheAppBelow() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        var closed = 0
        val root = ReadingBackAwareLayout(owner.get()) { closed++ }
        assertTrue(root.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(1, closed)
        assertFalse(root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
        owner.pause().stop().destroy()
    }

    @Test fun modelProgressDoesNotResetTheReadingTextTabOrFirePreferenceCallbacks() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        var changes = 0
        val controls = ReadingOverlayControls(owner.get(), callbacks(changed = { changes++ }))
        owner.get().setContentView(controls)
        controls.translatedText.text = "ترجمة محفوظة"
        controls.originalText.text = "Saved original"
        controls.showReadingText()
        repeat(3) {
            controls.render(ReadingOverlayUiState(working = it == 1, paused = true, passThrough = false,
                mode = "page", textSize = 24f, showOriginal = true, historyCount = 2,
                compactHandle = true, changeSensitivity = "stable"))
        }
        assertEquals(0, changes)
        assertEquals("ترجمة محفوظة", controls.translatedText.text.toString())
        assertEquals("Saved original", controls.originalText.text.toString())
        assertTrue(controls.translatedText.isShown)
        assertEquals(24f, controls.translatedText.textSize / controls.resources.displayMetrics.scaledDensity, .1f)
        val textParent = controls.translatedText.parent as View
        assertEquals(View.VISIBLE, textParent.visibility)
        owner.pause().stop().destroy()
    }

    @Test fun compactHandleKeepsSeparateRefreshOptionsAndDragTargets() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        var translated = 0
        var opened = 0
        val handle = ReadingOverlayHandle(owner.get(), { translated++ }, { opened++ })
        handle.render(ReadingOverlayUiState(compactHandle = true, paused = true))
        owner.get().setContentView(handle)
        handle.measure(View.MeasureSpec.makeMeasureSpec(dp(handle, 144), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(handle, 200), View.MeasureSpec.AT_MOST))
        handle.layout(0, 0, handle.measuredWidth, handle.measuredHeight)
        assertEquals(View.GONE, handle.primaryButton.visibility)
        val visible = descendants(handle).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
        assertEquals(2, visible.size)
        visible.forEach {
            assertTrue(it.measuredHeight >= dp(handle, 48))
            assertTrue(it.right <= handle.width)
            it.performClick()
        }
        assertEquals(1, translated)
        assertEquals(1, opened)
        assertTrue(handle.dragTarget.measuredHeight >= dp(handle, 48))
        assertTrue(handle.dragTarget.contentDescription.contains(owner.get().getString(R.string.reading_overlay_paused)))
        owner.pause().stop().destroy()
    }

    @Test fun narrowControlWindowScrollsAndEveryInteractiveButtonHasATouchTarget() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = ReadingOverlayControls(owner.get(), callbacks())
        owner.get().setContentView(controls)
        controls.measure(View.MeasureSpec.makeMeasureSpec(dp(controls, 296), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(controls, 440), View.MeasureSpec.EXACTLY))
        controls.layout(0, 0, controls.measuredWidth, controls.measuredHeight)
        val scroll = descendants(controls).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.height > 0)
        assertTrue(scroll.getChildAt(0).measuredHeight > scroll.height)
        descendants(controls).filterIsInstance<Button>().filter { it.isShown }.forEach {
            assertTrue("${it.text} must retain a 48 dp touch height", it.measuredHeight >= dp(controls, 48))
        }
        val passThrough = descendants(controls).filterIsInstance<Switch>()
            .first { it.text == owner.get().getString(R.string.reading_overlay_passthrough) }
        assertTrue(passThrough.measuredHeight >= dp(controls, 48))
        owner.pause().stop().destroy()
    }

    @Test fun onlyTheOpenPanelRequestsKeyboardFocus() {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val panel = ReadingOverlayControls.focusableFlags(base)
        assertEquals(0, panel and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertNotEquals(0, panel and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        assertNotEquals(0, panel and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        assertNotEquals(0, panel and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        assertNotEquals(0, panel and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    private fun callbacks(dismiss: () -> Unit = {}, translate: () -> Unit = {}, changed: () -> Unit = {}) =
        ReadingOverlayCallbacks(translate = translate, pause = {}, passThrough = { changed() },
            visibility = { changed() }, showOriginal = { changed() }, selectArea = {}, wholePage = {}, clear = {},
            mode = { changed() }, textSize = { changed() }, settleMs = { changed() }, scanMs = { changed() },
            refreshCooldownMs = { changed() }, changeSensitivity = { changed() }, compactHandle = { changed() },
            copy = {}, speak = { _, _ -> }, stopVoice = {}, history = {}, openSetup = {}, openHearth = {}, stop = {}, dismiss = dismiss)

    private fun descendants(root: ViewGroup): List<View> = buildList {
        repeat(root.childCount) { index ->
            val view = root.getChildAt(index)
            add(view)
            if (view is ViewGroup) addAll(descendants(view))
        }
    }
    private fun button(root: ViewGroup, label: Int): Button = descendants(root).filterIsInstance<Button>()
        .single { it.text == root.context.getString(label) }
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(view, height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun tap(view: View) {
        val down = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        val release = MotionEvent.obtain(down, down + 30, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(press)
            view.dispatchTouchEvent(release)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { press.recycle(); release.recycle() }
    }
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
