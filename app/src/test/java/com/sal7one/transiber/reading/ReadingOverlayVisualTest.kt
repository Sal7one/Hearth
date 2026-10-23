package com.sal7one.transiber.reading

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import com.sal7one.transiber.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Optional review PNGs use the real runtime views, not a separately maintained mockup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingOverlayVisualTest {
    @Test fun englishControlsFitWithoutClipping() = withActivity { activity ->
        val view = ReadingOverlayControls(localized(activity, "en", dark = true), callbacks())
        activity.setContentView(view)
        view.statusText.text = "Page translated · 12 text regions · no new page changes"
        view.render(ReadingOverlayUiState(mode = "page", languagePair = "English → Arabic", historyCount = 4))
        render(view, "controls-en-dark", 400, 700)
        assertVisibleTargetsFit(view)
    }

    @Test fun arabicNarrowControlsUseRtlAndScroll() = withActivity { activity ->
        val view = ReadingOverlayControls(localized(activity, "ar", dark = false), callbacks())
        activity.setContentView(view)
        view.statusText.text = "تمت ترجمة الصفحة · ١٢ منطقة نصية"
        view.render(ReadingOverlayUiState(languagePair = "الإنجليزية ← العربية", historyCount = 4))
        render(view, "controls-ar-narrow-light", 296, 560)
        assertEquals(View.LAYOUT_DIRECTION_RTL, view.layoutDirection)
        assertVisibleTargetsFit(view)
    }

    @Test fun textComparisonAndAdvancedControlsRemainReadable() = withActivity { activity ->
        val view = ReadingOverlayControls(localized(activity, "en", dark = true), callbacks())
        activity.setContentView(view)
        view.statusText.text = "Showing the last translated page"
        view.render(ReadingOverlayUiState(mode = "page", languagePair = "English → Arabic", historyCount = 4))
        val automation = descendants(view).filterIsInstance<Button>().single { it.text == "Automatic translation" }
        automation.performClick()
        render(view, "controls-en-automation", 400, 720, scrollTo = automation)
        view.originalText.text = "The museum opens at nine in the morning. Please bring your ticket to the east entrance."
        view.translatedText.text = "يفتح المتحف في التاسعة صباحًا. يُرجى إحضار تذكرتك إلى المدخل الشرقي."
        view.showReadingText()
        render(view, "reading-text-ar-en", 400, 700)
        assertVisibleTargetsFit(view)
    }

    @Test fun compactAndExpandedHandlesFitAllActions() = withActivity { activity ->
        val context = localized(activity, "en", dark = true)
        val view = ReadingOverlayHandle(context, {}, {})
        activity.setContentView(view)
        view.render(ReadingOverlayUiState(compactHandle = true, paused = true))
        render(view, "handle-compact-paused", 144, 48)
        assertVisibleTargetsFit(view)
        view.render(ReadingOverlayUiState(compactHandle = false, mode = "page"))
        render(view, "handle-expanded", 240, 104)
        assertVisibleTargetsFit(view)
    }

    private fun render(view: View, name: String, width: Int, height: Int, scrollTo: View? = null) {
        val density = view.resources.displayMetrics.density
        val pixelsWide = (width * density).toInt()
        val pixelsHigh = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(pixelsWide, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pixelsHigh, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, pixelsWide, pixelsHigh)
        if (scrollTo != null && view is ViewGroup) {
            descendants(view).filterIsInstance<ScrollView>().single().scrollTo(0, scrollTo.top)
        }
        val bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            view.draw(Canvas(bitmap))
            val folder = System.getenv("HEARTH_READING_UI_ARTIFACTS") ?: return
            File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun assertVisibleTargetsFit(root: ViewGroup) {
        descendants(root).filterIsInstance<Button>().filter { it.isShown }.forEach { button ->
            val parent = button.parent as View
            assertTrue("${button.text} clips at start", button.left >= 0)
            assertTrue("${button.text} clips at end", button.right <= parent.width)
            assertTrue("${button.text} touch target is too short", button.height >= 48 * button.resources.displayMetrics.density)
        }
    }

    private fun localized(context: Context, language: String, dark: Boolean): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })

    private fun withActivity(block: (Activity) -> Unit) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try { block(owner.get()) } finally { owner.pause().stop().destroy() }
    }

    private fun callbacks() = ReadingOverlayCallbacks(translate = {}, pause = {}, passThrough = {}, visibility = {},
        showOriginal = {}, selectArea = {}, wholePage = {}, clear = {}, mode = {}, textSize = {}, settleMs = {}, scanMs = {},
        refreshCooldownMs = {}, changeSensitivity = {}, compactHandle = {}, copy = {}, speak = { _, _ -> }, stopVoice = {},
        history = {}, openSetup = {}, openHearth = {}, stop = {}, dismiss = {})

    private fun descendants(root: ViewGroup): List<View> = buildList {
        repeat(root.childCount) { index ->
            val view = root.getChildAt(index)
            add(view)
            if (view is ViewGroup) addAll(descendants(view))
        }
    }
}
