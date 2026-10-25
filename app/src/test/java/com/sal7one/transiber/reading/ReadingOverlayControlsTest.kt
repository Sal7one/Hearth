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
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sal7one.transiber.R
import com.sal7one.transiber.ui.components.HearthColourPickerView
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
    @Test fun replacementKeepsTouchThroughEditableWithoutOverwritingSavedOpacity() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var writes = 0
        val touchChoices = mutableListOf<Boolean>()
        val controls = ReadingOverlayControls(owner.get(), callbacks(changed = { writes++ }).copy(
            appearance = { writes++ }, passThrough = { touchChoices += it }))
        owner.get().setContentView(controls)
        val saved = ReadingOverlayUiState(replaceText = true, passThrough = true, boxOpacity = 25)
        controls.render(saved)
        val pass = descendants(controls).filterIsInstance<Switch>().single {
            it.text == owner.get().getString(R.string.reading_overlay_passthrough)
        }
        val opacity = descendants(controls).filterIsInstance<SeekBar>().single {
            it.contentDescription == owner.get().getString(R.string.reading_style_opacity)
        }
        assertTrue(pass.isEnabled); assertTrue(pass.isChecked)
        assertFalse(opacity.isEnabled); assertEquals(opacity.max, opacity.progress)
        pass.performClick()
        assertEquals(listOf(false), touchChoices)
        controls.render(saved.copy(passThrough = false))
        assertTrue(pass.isEnabled); assertFalse(pass.isChecked)
        assertFalse(opacity.isEnabled); assertEquals(opacity.max, opacity.progress)
        pass.performClick()
        assertEquals(listOf(false, true), touchChoices)
        controls.render(saved.copy(showOriginal = true))
        assertTrue(pass.isEnabled); assertTrue(pass.isChecked)
        controls.render(saved.copy(translationsVisible = false))
        assertTrue(pass.isEnabled); assertTrue(pass.isChecked)
        controls.render(saved.copy(replaceText = false))
        assertTrue(pass.isEnabled); assertTrue(pass.isChecked)
        assertTrue(opacity.isEnabled); assertEquals(3, opacity.progress)
        assertEquals("Rendering effective opacity must not overwrite saved preferences", 0, writes)
        owner.pause().stop().destroy()
    }

    @Test fun opaqueReplacementAddsAnAccessibleEyeWithinTheCompactHandle() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var toggles = 0
        val handle = ReadingOverlayHandle(owner.get(), {}, {}, onToggleTranslations = { toggles++ })
        owner.get().setContentView(handle)
        handle.render(ReadingOverlayUiState(replaceText = true, compactHandle = true))
        measure(handle, 204, 56)
        val eye = handle.findViewWithTag<View>("reading-layer-visibility")
        assertTrue(eye.isShown)
        assertTrue(eye.measuredWidth >= dp(handle, 48)); assertTrue(eye.measuredHeight >= dp(handle, 48))
        assertTrue(eye.right <= (eye.parent as View).width)
        assertEquals(owner.get().getString(R.string.reading_style_hide_translation), eye.contentDescription)
        eye.performClick(); assertEquals(1, toggles)
        handle.render(ReadingOverlayUiState(replaceText = true, translationsVisible = false))
        assertEquals(owner.get().getString(R.string.reading_style_show_translation), eye.contentDescription)
        eye.performClick(); assertEquals(2, toggles)
        handle.render(ReadingOverlayUiState(replaceText = false))
        assertEquals(View.GONE, eye.visibility)
        owner.pause().stop().destroy()
    }

    @Test fun appearanceControlsAreLiveOptInAndPresetsKeepTheSavedGeometry() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val writes = mutableListOf<ReadingOverlaySettings>()
        var captures = 0
        val controls = ReadingOverlayControls(owner.get(), callbacks(translate = { captures++ }).copy(appearance = { writes += it }))
        owner.get().setContentView(controls)
        controls.render(ReadingOverlayUiState(replaceText = false, boxWidthPercent = 85, boxHeightPercent = 145))
        assertTrue(writes.isEmpty())
        button(controls, R.string.reading_overlay_appearance).performClick()
        val replacement = descendants(controls).filterIsInstance<Switch>()
            .single { it.text == owner.get().getString(R.string.reading_style_replace) }
        assertFalse(replacement.isChecked)
        replacement.performClick()
        assertEquals(1, writes.size); assertTrue(writes.last().replaceText)
        button(controls, R.string.reading_style_dark).performClick()
        assertEquals(2, writes.size)
        assertTrue(ReadingBoxPreset.DARK.matches(writes.last()))
        assertTrue(writes.last().replaceText)
        assertEquals(85, writes.last().boxWidthPercent); assertEquals(145, writes.last().boxHeightPercent)
        assertEquals(0, captures)
        owner.pause().stop().destroy()
    }

    @Test fun colourPickerKeepsUnappliedHexAndCommitsOnlyItsColourAgainstLatestSettings() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val writes = mutableListOf<ReadingOverlaySettings>()
        val appearance = ReadingAppearanceControls(owner.get()) { writes += it }
        owner.get().setContentView(appearance)
        val original = ReadingOverlaySettings(replaceText = true, boxWidthPercent = 85,
            boxHeightPercent = 145, boxOpacity = 70, mode = "page", passThrough = false)
        appearance.render(original)
        val background = tagged<Button>(appearance, "reading-background-colour-picker")
        background.performClick()
        val backgroundPicker = shownPicker(appearance)
        assertTrue(background.isSelected)
        val hex = tagged<EditText>(backgroundPicker, "hearth_colour_hex")
        hex.setText("2471A3")
        assertTrue("Typing an optional hex value must not save before Apply", writes.isEmpty())

        val newer = original.copy(boxWidthPercent = 150, boxTextArgb = 0xfffedcba.toInt())
        appearance.render(newer)
        assertTrue("A progress render must keep the colour picker open", backgroundPicker.isShown)
        assertEquals("A service echo must not overwrite uncommitted input", "2471A3", hex.text.toString())
        assertTrue(writes.isEmpty())
        tagged<Button>(backgroundPicker, "hearth_colour_apply").performClick()
        assertEquals(listOf(newer.copy(boxBackgroundArgb = 0xff2471a3.toInt())), writes)

        tagged<Button>(appearance, "reading-text-colour-picker").performClick()
        val textPicker = shownPicker(appearance)
        assertFalse("Opening text colour must close the background picker", backgroundPicker.isShown)
        tagged<EditText>(textPicker, "hearth_colour_hex").setText("102030")
        tagged<Button>(textPicker, "hearth_colour_apply").performClick()
        assertEquals(2, writes.size)
        assertEquals(newer.copy(boxBackgroundArgb = 0xff2471a3.toInt(), boxTextArgb = 0xff102030.toInt()), writes.last())
        owner.pause().stop().destroy()
    }

    @Test fun colourPickerCanCloseOrSwitchWithoutApplyingUncommittedHex() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val writes = mutableListOf<ReadingOverlaySettings>()
        val appearance = ReadingAppearanceControls(owner.get()) { writes += it }
        owner.get().setContentView(appearance)
        val saved = ReadingOverlaySettings(boxBackgroundArgb = 0xff0a0b0c.toInt(),
            boxTextArgb = 0xfff0f1f2.toInt(), boxOpacity = 55)
        appearance.render(saved)
        val background = tagged<Button>(appearance, "reading-background-colour-picker")
        background.performClick()
        tagged<EditText>(shownPicker(appearance), "hearth_colour_hex").setText("FF00FF")
        background.performClick()
        assertFalse(background.isSelected)
        assertTrue(descendants(appearance).filterIsInstance<HearthColourPickerView>().none { it.isShown })
        assertTrue(writes.isEmpty())

        tagged<Button>(appearance, "reading-text-colour-picker").performClick()
        val picker = shownPicker(appearance)
        tagged<EditText>(picker, "hearth_colour_hex").setText("765432")
        tagged<Button>(picker, "hearth_colour_apply").performClick()
        assertEquals(listOf(saved.copy(boxTextArgb = 0xff765432.toInt())), writes)
        owner.pause().stop().destroy()
    }

    @Test fun colourDragSurvivesAProgressRenderAndCommitsAgainstTheLatestGeometry() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val writes = mutableListOf<ReadingOverlaySettings>()
        val appearance = ReadingAppearanceControls(owner.get()) { writes += it }
        owner.get().setContentView(appearance)
        val saved = ReadingOverlaySettings(boxBackgroundArgb = 0xff00aabb.toInt(),
            boxTextArgb = 0xffeeddcc.toInt(), replaceText = true, boxOpacity = 60)
        appearance.render(saved)
        tagged<Button>(appearance, "reading-background-colour-picker").performClick()
        measure(appearance, 296, 2_000)
        val picker = shownPicker(appearance)
        val field = tagged<View>(picker, "hearth_colour_sv")
        val downAt = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN,
            field.width * .4f, field.height * .3f, 0)
        val release = MotionEvent.obtain(downAt, downAt + 40, MotionEvent.ACTION_UP,
            field.width * .6f, field.height * .7f, 0)
        try {
            assertTrue(field.dispatchTouchEvent(press))
            assertTrue("A colour preview must not persist every pointer movement", writes.isEmpty())
            appearance.render(saved.copy(boxWidthPercent = 180, boxHeightPercent = 190))
            assertTrue(picker.isShown)
            assertTrue("A service render must not cancel the active colour drag", field.dispatchTouchEvent(release))
        } finally { press.recycle(); release.recycle() }
        assertEquals(1, writes.size)
        val committed = writes.single()
        assertNotEquals(saved.boxBackgroundArgb, committed.boxBackgroundArgb)
        assertEquals(saved.copy(boxWidthPercent = 180, boxHeightPercent = 190,
            boxBackgroundArgb = committed.boxBackgroundArgb), committed)
        owner.pause().stop().destroy()
    }

    @Test fun appearanceColourPickerKeepsAllNativeControlsWithinANarrowSheet() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val controls = ReadingOverlayControls(owner.get(), callbacks())
        owner.get().setContentView(controls)
        button(controls, R.string.reading_overlay_appearance).performClick()
        tagged<Button>(controls, "reading-background-colour-picker").performClick()
        measure(controls, 296, 440)
        val scroll = descendants(controls).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        val appearance = descendants(controls).filterIsInstance<ReadingAppearanceControls>().single()
        descendants(appearance).filterIsInstance<Button>().filter { it.isShown }.forEach { button ->
            assertTrue(button.measuredHeight >= dp(controls, 48))
            assertTrue(button.width > 0)
            assertTrue(button.right <= (button.parent as View).width)
        }
        val picker = shownPicker(appearance)
        val saturation = tagged<View>(picker, "hearth_colour_sv")
        val hue = tagged<View>(picker, "hearth_colour_hue")
        assertTrue("The visual colour field must be usable on a narrow sheet", saturation.width >= dp(controls, 120))
        assertTrue(saturation.height >= dp(controls, 96))
        assertTrue(hue.width > 0)
        assertTrue(hue.height >= dp(controls, 48))
        assertTrue(tagged<EditText>(picker, "hearth_colour_hex").width > 0)
        owner.pause().stop().destroy()
    }

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
        assertEquals("The options picker must be able to open the optional hex keyboard", 0,
            panel and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
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
    private inline fun <reified T : View> tagged(root: ViewGroup, tag: String): T =
        descendants(root).filterIsInstance<T>().single { it.tag == tag }
    private fun shownPicker(root: ViewGroup): HearthColourPickerView =
        descendants(root).filterIsInstance<HearthColourPickerView>().single { it.isShown }
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
