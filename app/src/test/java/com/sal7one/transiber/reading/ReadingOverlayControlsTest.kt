package com.sal7one.transiber.reading

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import com.sal7one.transiber.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingOverlayControlsTest {
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
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
