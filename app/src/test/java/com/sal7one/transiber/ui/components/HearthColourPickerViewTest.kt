package com.sal7one.transiber.ui.components

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HearthColourPickerViewTest {
    @Test fun draggingPreviewsContinuouslyAndOnlyPointerUpCommits() = withActivity { activity ->
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, previews::add, commits::add)
        val parent = TouchParent(activity)
        parent.addView(picker)
        activity.setContentView(parent)
        layout(picker)
        val sv = picker.findViewWithTag<View>("hearth_colour_sv")
        touch(sv, MotionEvent.ACTION_DOWN, 120f, 50f)
        assertTrue(parent.disallowed.last())
        assertEquals(0, commits.size)
        val first = previews.last()
        touch(sv, MotionEvent.ACTION_MOVE, 205f, 140f)
        assertNotEquals(first, previews.last())
        assertEquals(0, commits.size)
        touch(sv, MotionEvent.ACTION_UP, 205f, 140f)
        assertEquals(listOf(previews.last()), commits)
        assertFalse(parent.disallowed.last())
        assertEquals(255, Color.alpha(commits.single()))
    }

    @Test fun cancelledDragRestoresPreviewAndNeverSaves() = withActivity { activity ->
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.BLUE, previews::add, commits::add)
        layout(picker)
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        touch(hue, MotionEvent.ACTION_DOWN, 110f, 24f)
        assertNotEquals(Color.BLUE, previews.last())
        touch(hue, MotionEvent.ACTION_CANCEL, 110f, 24f)
        assertEquals(Color.BLUE, previews.last())
        assertTrue(commits.isEmpty())
        assertEquals("#0000FF", picker.findViewWithTag<EditText>("hearth_colour_hex").text.toString())
    }

    @Test fun serviceRendersDoNotCancelAnActiveDragOrCommitItsIntermediateColour() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, onCommit = commits::add)
        layout(picker)
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        touch(hue, MotionEvent.ACTION_DOWN, 106f, 24f)
        picker.setColour(Color.RED)
        touch(hue, MotionEvent.ACTION_MOVE, 153f, 24f)
        picker.setColour(Color.RED)
        touch(hue, MotionEvent.ACTION_UP, 153f, 24f)
        assertEquals(1, commits.size)
        assertNotEquals(Color.RED, commits.single())
    }

    @Test fun hidingAnAttachedPickerCancelsItsDragAndAllowsFreshStateWhenItReopens() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val previews = mutableListOf<Int>()
        val parent = TouchParent(activity)
        val picker = HearthColourPickerView(activity, Color.RED, previews::add, commits::add)
        parent.addView(picker)
        activity.setContentView(parent)
        layout(picker)
        assertTrue(picker.isAttachedToWindow)
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        touch(hue, MotionEvent.ACTION_DOWN, 106f, 24f)
        assertNotEquals(Color.RED, previews.last())
        assertTrue(parent.disallowed.last())

        picker.visibility = View.GONE
        assertEquals("Hiding restores the uncommitted preview", Color.RED, previews.last())
        assertFalse("The hidden control releases its parent's scroll interception", parent.disallowed.last())
        assertTrue(commits.isEmpty())
        picker.setColour(Color.BLUE)
        picker.visibility = View.VISIBLE
        assertEquals("A hidden pointer must not block subsequent external state", "#0000FF",
            picker.findViewWithTag<EditText>("hearth_colour_hex").text.toString())

        touch(hue, MotionEvent.ACTION_DOWN, 103f, 24f)
        touch(hue, MotionEvent.ACTION_UP, 103f, 24f)
        assertEquals(1, commits.size)
        assertEquals(120f, hsv(commits.single())[0], 2f)
    }

    @Test fun sameBlackColourDoesNotResetTheHueChosenBeforeAddingSaturationAndBrightness() = withActivity { activity ->
        lateinit var picker: HearthColourPickerView
        val commits = mutableListOf<Int>()
        picker = HearthColourPickerView(activity, Color.BLACK, onCommit = { commits += it; picker.setColour(it) })
        layout(picker)
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        assertTrue(hue.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 240f) }))
        assertEquals(Color.BLACK, commits.single())
        val sv = picker.findViewWithTag<View>("hearth_colour_sv")
        touch(sv, MotionEvent.ACTION_DOWN, 240f, 8f)
        touch(sv, MotionEvent.ACTION_UP, 240f, 8f)
        val selected = hsv(commits.last())
        assertEquals(240f, selected[0], 1f)
        assertTrue(selected[1] > .95f && selected[2] > .95f)
    }

    @Test fun hexRequiresExplicitValidApplyAndExternalRendersPreserveTheDraft() = withActivity { activity ->
        val previews = mutableListOf<Int>()
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, previews::add, commits::add)
        val hex = picker.findViewWithTag<EditText>("hearth_colour_hex")
        val apply = picker.findViewWithTag<Button>("hearth_colour_apply")
        hex.setText("#12GG56")
        assertTrue(commits.isEmpty())
        apply.performClick()
        assertNotNull(hex.error)
        assertTrue(commits.isEmpty())
        hex.setText("20b2aa")
        picker.setColour(Color.RED)
        picker.setColour(Color.BLUE)
        assertEquals("20b2aa", hex.text.toString())
        assertTrue(previews.isEmpty())
        assertTrue(commits.isEmpty())
        apply.performClick()
        assertEquals(listOf(Color.rgb(32, 178, 170)), commits)
        assertEquals(commits, previews)
        assertNull(hex.error)
        assertEquals("#20B2AA", hex.text.toString())
    }

    @Test fun imeDoneAppliesOpaqueColourAndNonFocusableHostsCanDisableHex() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, 0x00123456, onCommit = commits::add)
        assertEquals("#123456", picker.findViewWithTag<EditText>("hearth_colour_hex").text.toString())
        val hex = picker.findViewWithTag<EditText>("hearth_colour_hex")
        hex.setText("ABCDEF")
        hex.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(listOf(Color.rgb(171, 205, 239)), commits)
        picker.setHexEnabled(false)
        assertEquals(View.GONE, (hex.parent as View).visibility)
        picker.setHexEnabled(true)
        assertEquals(View.VISIBLE, (hex.parent as View).visibility)
    }

    @Test fun dpadMakesContinuousPreviewButCommitsOnlyAfterTheCompletedKey() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val previews = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, previews::add, commits::add)
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        assertTrue(hue.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(previews.isNotEmpty())
        assertTrue(commits.isEmpty())
        assertTrue(hue.onKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(listOf(previews.last()), commits)
        assertEquals(3f, hsv(commits.single())[0], .5f)
    }

    @Test fun remoteOkAndEnterMoveThroughControlsWithoutCommittingOrChangingTouchFocus() = withRemoteActivity { activity ->
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, onCommit = commits::add)
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val nextButton = Button(activity).apply { text = "Next control" }
        parent.addView(picker)
        parent.addView(nextButton)
        activity.setContentView(parent)
        layout(parent)
        val plane = picker.findViewWithTag<View>("hearth_colour_sv")
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        val hex = picker.findViewWithTag<EditText>("hearth_colour_hex")

        fun advance(view: View, key: Int) {
            assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
            assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
        }
        assertFalse("Remote navigation uses Android's non-touch focus mode", plane.isInTouchMode)
        assertTrue(plane.requestFocus())
        advance(plane, KeyEvent.KEYCODE_DPAD_CENTER)
        assertSame(hue, parent.findFocus())
        advance(hue, KeyEvent.KEYCODE_ENTER)
        assertSame(hex, parent.findFocus())
        assertTrue("Moving to another control does not save a colour", commits.isEmpty())

        picker.setHexEnabled(false)
        assertTrue(plane.requestFocus())
        advance(plane, KeyEvent.KEYCODE_ENTER)
        assertSame(hue, parent.findFocus())
        advance(hue, KeyEvent.KEYCODE_DPAD_CENTER)
        assertSame("Hidden hex controls are skipped", nextButton, parent.findFocus())
        assertTrue(commits.isEmpty())

        assertTrue(plane.requestFocus())
        touch(hue, MotionEvent.ACTION_DOWN, 103f, 24f)
        touch(hue, MotionEvent.ACTION_UP, 103f, 24f)
        assertSame("Touch release must not invoke remote focus navigation", plane, parent.findFocus())
        assertEquals(1, commits.size)
    }

    @Test fun talkBackCanIndependentlyAdjustBothPlaneAxesAndHue() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.HSVToColor(floatArrayOf(120f, .5f, .5f)), onCommit = commits::add)
        val plane = picker.findViewWithTag<View>("hearth_colour_sv")
        val node = plane.createAccessibilityNodeInfo()
        assertTrue(node.actionList.any { it.id == HearthColourPickerView.ACTION_SATURATION_UP })
        assertTrue(node.actionList.any { it.id == HearthColourPickerView.ACTION_BRIGHTNESS_UP })
        assertTrue(plane.performAccessibilityAction(HearthColourPickerView.ACTION_SATURATION_UP, null))
        val saturation = hsv(commits.last())
        assertTrue(saturation[1] > .51f)
        assertTrue(plane.performAccessibilityAction(HearthColourPickerView.ACTION_BRIGHTNESS_UP, null))
        assertTrue(hsv(commits.last())[2] > saturation[2])
        val hue = picker.findViewWithTag<View>("hearth_colour_hue")
        val hueNode = hue.createAccessibilityNodeInfo()
        assertNotNull(hueNode.rangeInfo)
        assertEquals(360f, hueNode.rangeInfo.max, 0f)
        assertTrue(hue.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 180f) }))
        assertEquals(180f, hsv(commits.last())[0], 1f)
        assertEquals(3, commits.size)
    }

    @Test fun nativeRenderShowsRealTwoAxisGradientHueRainbowAndVisibleThumb() = withActivity { activity ->
        val picker = HearthColourPickerView(activity, Color.HSVToColor(floatArrayOf(0f, .5f, .5f)), onCommit = {})
        activity.setContentView(picker)
        layout(picker)
        val plane = picker.findViewWithTag<View>("hearth_colour_sv")
        val pixels = draw(plane)
        try {
            val topLeft = hsv(pixels.getPixel(62, 13))
            val topRight = hsv(pixels.getPixel(234, 13))
            val bottomRight = hsv(pixels.getPixel(234, 185))
            assertTrue("White to full hue horizontally", topLeft[1] < .1f && topRight[1] > .9f)
            assertTrue("Brightness changes independently vertically", topRight[2] > .9f && bottomRight[2] < .1f)
            // HSV is quantized through the initial RGB colour, moving the thumb by
            // a fraction of a pixel. Check its two high-contrast rings, rather than
            // one anti-aliased edge pixel whose coverage depends on that fraction.
            val selection = hsv(Color.HSVToColor(floatArrayOf(0f, .5f, .5f)))
            val thumbX = 58f + selection[1] * 184f
            val thumbY = 8f + (1 - selection[2]) * 184f
            var whiteRing = 0
            var blackRing = 0
            for (y in (thumbY.toInt() - 10)..(thumbY.toInt() + 10)) {
                for (x in (thumbX.toInt() - 10)..(thumbX.toInt() + 10)) {
                    val distanceSquared = (x + .5f - thumbX) * (x + .5f - thumbX) +
                        (y + .5f - thumbY) * (y + .5f - thumbY)
                    if (distanceSquared in 16f..100f) {
                        val luminance = Color.luminance(pixels.getPixel(x, y))
                        if (luminance > .8) whiteRing++
                        if (luminance < .03) blackRing++
                    }
                }
            }
            assertTrue("Thumb has a visible white ring at the selected colour", whiteRing >= 12)
            assertTrue("Thumb has a visible black outline at the selected colour", blackRing >= 12)
            val hue = picker.findViewWithTag<View>("hearth_colour_hue")
            assertTrue(hue.height >= 48)
            val rainbow = draw(hue)
            try {
                assertTrue(Color.red(rainbow.getPixel(12, 24)) > 230)
                assertTrue(Color.green(rainbow.getPixel(103, 24)) > 230)
                assertTrue(Color.blue(rainbow.getPixel(198, 24)) > 230)
            } finally { rainbow.recycle() }
        } finally { pixels.recycle() }
    }

    @Test fun narrowRtlLayoutRetainsUsablePlaneHexApplyAndTouchCoordinates() = withActivity { activity ->
        val commits = mutableListOf<Int>()
        val picker = HearthColourPickerView(activity, Color.RED, onCommit = commits::add)
        picker.layoutDirection = View.LAYOUT_DIRECTION_RTL
        activity.setContentView(picker)
        layout(picker, 180)
        val plane = picker.findViewWithTag<View>("hearth_colour_sv")
        assertEquals(180, plane.width)
        assertEquals(180, plane.height)
        val hex = picker.findViewWithTag<EditText>("hearth_colour_hex")
        val apply = picker.findViewWithTag<Button>("hearth_colour_apply")
        assertTrue(hex.width >= 48)
        assertTrue(apply.width >= 48)
        assertEquals(View.LAYOUT_DIRECTION_LTR, hex.layoutDirection)
        touch(plane, MotionEvent.ACTION_DOWN, 171f, 8f)
        touch(plane, MotionEvent.ACTION_UP, 171f, 8f)
        assertTrue(hsv(commits.single())[1] > .95f)
    }

    @Test fun productionPickerCanRenderLightAndDarkReviewImagesWithoutDeviceAccess() = withActivity { activity ->
        val output = System.getenv("HEARTH_PICKER_PREVIEW_DIR")?.let(::File)
        for (light in listOf(false, true)) {
            val picker = HearthColourPickerView(activity, Color.rgb(32, 178, 170), onCommit = {})
            val surface = if (light) Color.rgb(245, 247, 250) else Color.rgb(25, 30, 38)
            picker.setPalette(if (light) Color.rgb(25, 30, 38) else Color.WHITE, surface, Color.rgb(32, 178, 170))
            picker.setBackgroundColor(surface)
            picker.setPadding(12, 12, 12, 12)
            activity.setContentView(picker)
            layout(picker, 296)
            val image = draw(picker)
            try {
                assertTrue(image.width == 296 && image.height in 360..550)
                val sample = picker.findViewWithTag<View>("hearth_colour_sample")
                val colour = (sample.background as GradientDrawable).color!!.defaultColor
                assertEquals(Color.rgb(32, 178, 170), colour)
                val hex = picker.findViewWithTag<EditText>("hearth_colour_hex")
                assertTrue(hex.width >= 48 && hex.height >= 48)
                if (output != null) {
                    output.mkdirs()
                    File(output, "picker-${if (light) "light" else "dark"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { image.recycle() }
        }
    }

    private fun layout(view: View, width: Int = 300) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
    }
    private fun draw(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun hsv(colour: Int) = FloatArray(3).also { Color.colorToHSV(colour, it) }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, x, y, 0).also { try { assertTrue(view.dispatchTouchEvent(it)) } finally { it.recycle() } }
    }
    private class TouchParent(context: android.content.Context) : LinearLayout(context) {
        val disallowed = mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
    private inline fun withActivity(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }
    private inline fun withRemoteActivity(block: (Activity) -> Unit) {
        // Set this before adding the window: Robolectric's Instrumentation shadow
        // changes the session default, while existing ViewRoots retain their mode.
        Instrumentation().setInTouchMode(false)
        withActivity(block)
    }
}
