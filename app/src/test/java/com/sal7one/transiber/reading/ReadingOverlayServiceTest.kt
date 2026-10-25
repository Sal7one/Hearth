package com.sal7one.transiber.reading

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.projection.MediaProjection
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import com.sal7one.transiber.R
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.hearth.core.vision.ComicMask
import com.sal7one.hearth.core.vision.MangaPaint
import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.transiber.ocr.TranslatedOcrBox
import com.sal7one.transiber.ocr.PixelCrop
import com.sal7one.transiber.ocr.CameraOcrController
import com.sal7one.transiber.ocr.CameraOcrState
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrPreferences
import com.sal7one.transiber.ocr.OcrSelection
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.translation.ConversationTranslatorSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the actual service/window callbacks without screen capture, models or device permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ReadingOverlayServiceTest {
    @Test fun backRestoresTheExistingPageWithoutStartingAnotherCapture() = withWindows { service ->
        val panel = field<ReadingOverlayControls>(service, "panel")
        val handle = field<ReadingOverlayHandle>(service, "handle")
        val layer = field<ReadingTranslationView>(service, "pageView")
        ReflectionHelpers.setField(service, "pageCrop", PixelCrop(0, 0, 100, 100))
        // A non-null host projection token ensures the requestCapture guard would not
        // hide a regression. We never start it, acquire a display or capture a frame.
        ReflectionHelpers.setField(service, "projection", ReflectionHelpers.newInstance(MediaProjection::class.java))
        val generation = field<Long>(service, "generation")
        call(service, "showPanel")
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, handle.visibility)
        assertEquals(0, field<WindowManager.LayoutParams>(service, "panelParams").flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(View.GONE, panel.visibility)
        assertEquals(View.VISIBLE, handle.visibility)
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(generation, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
        assertFalse(service.paused)
        assertNotEquals(0, field<WindowManager.LayoutParams>(service, "panelParams").flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Test fun actualSwitchCallbacksPersistAndApplyLayerChoicesWithoutAnotherOcrRun() = withWindows { service ->
        val panel = field<ReadingOverlayControls>(service, "panel")
        val layer = field<ReadingTranslationView>(service, "pageView")
        val preferences = ReadingOverlayPreferences(service)
        ReflectionHelpers.setField(service, "pageCrop", PixelCrop(0, 0, 100, 100))
        val generation = field<Long>(service, "generation")
        fun change(label: Int, checked: Boolean) {
            descendants(panel).filterIsInstance<Switch>().single { it.text == service.getString(label) }.isChecked = checked
            // onStartCommand normally observes this exact DataStore snapshot. This
            // fixture starts no projection, so drive that observer boundary explicitly.
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, preferences.read()))
        }
        change(R.string.reading_overlay_passthrough, false)
        assertFalse(preferences.read().passThrough)
        assertEquals(0, field<WindowManager.LayoutParams>(service, "layerParams").flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        change(R.string.reading_overlay_original, true)
        assertTrue(preferences.read().showOriginal)
        assertTrue(layer.showOriginal)
        change(R.string.reading_overlay_show_translation, false)
        assertFalse(preferences.read().translationVisible)
        call(service, "showRegions")
        assertEquals("Late region progress must respect hidden translation", View.INVISIBLE, layer.visibility)
        val boxes = listOf(TranslatedOcrBox(OcrLine(0, 0, 20, 10, "Hello", 1f), "مرحبا"))
        call(service, "showTranslations", ClassParameter.from(List::class.java, boxes))
        assertEquals("Late translation progress must respect hidden translation", View.INVISIBLE, layer.visibility)
        call(service, "resumeReader")
        assertEquals(View.INVISIBLE, layer.visibility)
        change(R.string.reading_overlay_show_translation, true)
        assertEquals("Original mode must not leave an invisible full-screen touch target", View.INVISIBLE, layer.visibility)
        change(R.string.reading_overlay_original, false)
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(generation, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
        assertEquals(preferences.read(), ReadingOverlayPreferences(service).read())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun replacementHonoursTouchThroughWhileCleanupAndSavedBoxChoicesStayOpaque() = withWindows { service ->
        val preferences = ReadingOverlayPreferences(service)
        val saved = ReadingOverlaySettings(passThrough = true, boxOpacity = 25,
            boxBackgroundArgb = Color.rgb(30, 60, 90))
        preferences.write(saved)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
        val layer = field<ReadingTranslationView>(service, "pageView")
        layer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        layer.layout(0, 0, 400, 400)
        // Admission normally follows an attached, measured window. Publishing the
        // fixture before its first layout postpones render() to a later traversal.
        completePage(service)
        call(service, "showTranslations", ClassParameter.from(List::class.java,
            field<List<TranslatedOcrBox>>(service, "renderedBoxes")))
        assertEquals(1, layer.childCount)
        val params = field<WindowManager.LayoutParams>(service, "layerParams")
        val passThroughAlpha = params.alpha
        assertTrue(passThroughAlpha <= .8f)
        assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(63, Color.alpha(layer.renderedLabels().single().background))
        val generation = field<Long>(service, "generation")

        val matching = saved.copy(replaceText = true)
        preferences.write(matching)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, matching))
        assertEquals(passThroughAlpha, params.alpha, 0f)
        assertNotEquals("Replacement must leave scrolling and taps to the underlying app", 0,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(255, Color.alpha(layer.renderedLabels().single().background))
        assertTrue("The owner's touch-through choice remains saved", preferences.read().passThrough)
        assertEquals(25, preferences.read().boxOpacity)
        val revision = field<Long>(service, "paintRevision")
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, matching))
        assertEquals("Reapplying the same replacement must not trigger self-motion", revision, field<Long>(service, "paintRevision"))

        // The real controls callback can still select interactive, unfaded text
        // without removing the replacement or recapturing the completed page.
        val toggle = descendants(field<ReadingOverlayControls>(service, "panel"))
            .filterIsInstance<Switch>().single { it.text == service.getString(R.string.reading_overlay_passthrough) }
        assertTrue(toggle.isEnabled)
        toggle.isChecked = false
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, preferences.read()))
        assertFalse(preferences.read().passThrough)
        assertTrue(preferences.read().replaceText)
        assertEquals(1f, params.alpha, 0f)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(255, Color.alpha(layer.renderedLabels().single().background))
        toggle.isChecked = true
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, preferences.read()))
        assertEquals(matching, preferences.read())
        assertEquals(passThroughAlpha, params.alpha, 0f)
        assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        preferences.write(saved)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
        assertEquals(passThroughAlpha, params.alpha, 0f)
        assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(63, Color.alpha(layer.renderedLabels().single().background))
        assertEquals(saved, preferences.read())
        assertEquals(generation, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
    }

    @Test fun replacementRemainsTouchThroughAfterOriginalAndVisibilityChangesWithoutDiscardingThePage() = withWindows { service ->
        val preferences = ReadingOverlayPreferences(service)
        val saved = ReadingOverlaySettings(replaceText = true, passThrough = true, boxOpacity = 35)
        preferences.write(saved)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
        val page = completePage(service)
        val layer = field<ReadingTranslationView>(service, "pageView")
        val params = field<WindowManager.LayoutParams>(service, "layerParams")
        val generation = field<Long>(service, "generation")
        for (hidden in listOf(saved.copy(showOriginal = true), saved.copy(translationVisible = false))) {
            preferences.write(hidden)
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, hidden))
            assertEquals(View.INVISIBLE, layer.visibility)
            assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(params.alpha <= .8f)
            assertSame(page.completed, field<Any>(service, "completedPage"))
            assertEquals(generation, field<Long>(service, "generation"))
            assertNull(field<Job?>(service, "captureJob"))
            preferences.write(saved)
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
            assertEquals(View.VISIBLE, layer.visibility)
            assertTrue(params.alpha > 0f && params.alpha <= .8f)
            assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertSame(page.completed, field<Any>(service, "completedPage"))
        }
        assertEquals(saved, preferences.read())
    }

    @Test fun interactiveRegularBoxesUseTheirOwnOpacityWithoutAnAdditionalWindowFade() = withWindows { service ->
        val saved = ReadingOverlaySettings(passThrough = false, boxOpacity = 45)
        ReadingOverlayPreferences(service).write(saved)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
        val params = field<WindowManager.LayoutParams>(service, "layerParams")
        assertEquals(1f, params.alpha, 0f)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(saved, ReadingOverlayPreferences(service).read())
    }

    @Test fun replacementHandleEyeKeepsUnderlyingTouchesAndTheSameSnapshotWithoutRecapturing() = withWindows { service ->
        val preferences = ReadingOverlayPreferences(service)
        val saved = ReadingOverlaySettings(replaceText = true, passThrough = true)
        preferences.write(saved)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, saved))
        val page = completePage(service)
        val generation = field<Long>(service, "generation")
        val handle = field<ReadingOverlayHandle>(service, "handle")
        val eye = descendants(handle).single { it.tag == "reading-layer-visibility" }
        val layer = field<ReadingTranslationView>(service, "pageView")
        val params = field<WindowManager.LayoutParams>(service, "layerParams")
        assertEquals(View.VISIBLE, eye.visibility)
        assertEquals((204 * service.resources.displayMetrics.density).toInt(),
            field<WindowManager.LayoutParams>(service, "handleParams").width)

        eye.performClick()
        val hidden = preferences.read()
        assertFalse(hidden.translationVisible)
        assertTrue(hidden.passThrough)
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, hidden))
        assertEquals(View.INVISIBLE, layer.visibility)
        assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertSame(page.completed, field<Any>(service, "completedPage"))

        eye.performClick()
        assertEquals(saved, preferences.read())
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, preferences.read()))
        assertEquals(View.VISIBLE, layer.visibility)
        assertTrue(params.alpha > 0f && params.alpha <= .8f)
        assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertSame(page.completed, field<Any>(service, "completedPage"))
        assertEquals(generation, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun appearanceCallbackPersistsAndRepaintsTheSamePageWithoutReroutingOrRecapturing() = withWindows { service ->
        val preferences=ReadingOverlayPreferences(service)
        val initial=ReadingOverlaySettings(mode="page",passThrough=false,settleMs=1500,scanMs=1000)
        preferences.write(initial)
        call(service,"applyOptions",ClassParameter.from(ReadingOverlaySettings::class.java,initial))
        completePage(service)
        val layer=field<ReadingTranslationView>(service,"pageView")
        layer.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
        layer.layout(0,0,400,400)
        call(service,"showTranslations",ClassParameter.from(List::class.java,field<List<TranslatedOcrBox>>(service,"renderedBoxes")))
        val generation=field<Long>(service,"generation")
        val revision=field<Long>(service,"paintRevision")
        val panel=field<ReadingOverlayControls>(service,"panel")
        val callback=ReflectionHelpers.getField<ReadingOverlayCallbacks>(panel,"callbacks")
        callback.appearance(ReadingOverlaySettings(boxWidthPercent=170,boxHeightPercent=180,
            boxBackgroundArgb=Color.rgb(15,30,45),boxTextArgb=Color.YELLOW,boxOpacity=65))
        val saved=preferences.read()
        assertEquals(initial.mode,saved.mode);assertEquals(initial.settleMs,saved.settleMs)
        assertEquals(initial.scanMs,saved.scanMs);assertFalse(saved.passThrough)
        call(service,"applyOptions",ClassParameter.from(ReadingOverlaySettings::class.java,saved))
        assertEquals(Color.YELLOW,layer.renderedLabels().single().foreground)
        assertEquals(Color.argb(165,15,30,45),layer.renderedLabels().single().background)
        assertEquals(field<List<PixelCrop>>(service,"paintedMasks"),layer.motionExclusions())
        assertTrue(field<Long>(service,"paintRevision")>revision)
        val repainted=field<Long>(service,"paintRevision")
        call(service,"applyOptions",ClassParameter.from(ReadingOverlaySettings::class.java,saved))
        assertEquals("The same saved style must not look like the reader moved",repainted,field<Long>(service,"paintRevision"))
        assertEquals(generation,field<Long>(service,"generation"));assertNull(field<Job?>(service,"captureJob"))
        assertEquals("ترجمة محفوظة",layer.renderedLabels().single().text)
    }

    @Test fun completedPageKeepsOnlyItsBoundedColourGridAndForgetDropsIt() = withWindows { service ->
        val layer=field<ReadingTranslationView>(service,"pageView")
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val samples=try {ReadingColourSamples.from(bitmap)} finally {bitmap.recycle()}
        layer.sourceColours=samples
        completePage(service)
        layer.sourceColours=null
        call(service,"restoreCompletedPage")
        assertSame(samples,layer.sourceColours)
        assertEquals(Color.CYAN,layer.sourceColours!!.sample(PixelCrop(0,0,100,100))!!.background)
        call(service,"clearPage",ClassParameter.from(Boolean::class.javaPrimitiveType!!,true))
        assertNull(layer.sourceColours);assertNull(field<Any?>(service,"completedPage"))
    }

    @Test fun setupSuppressionAndRepeatedOptionsNavigationKeepTheCompletedPage() = withWindows { service ->
        call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = "page")))
        val saved = completePage(service)
        val region = RectF(.1f, .2f, .8f, .9f)
        ReflectionHelpers.setField(service, "region", region)
        val layer = field<ReadingTranslationView>(service, "pageView")
        val panel = field<ReadingOverlayControls>(service, "panel")
        val handle = field<ReadingOverlayHandle>(service, "handle")
        val firstGeneration = field<Long>(service, "generation")
        repeat(2) {
            call(service, "showPanel")
            assertEquals("Saved original", panel.originalText.text.toString())
            call(service, "resumeReader")
            assertEquals(firstGeneration, field<Long>(service, "generation"))
            assertEquals(View.VISIBLE, layer.visibility)
        }
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        assertEquals(View.GONE, handle.visibility)
        assertEquals(View.GONE, panel.visibility)
        assertEquals(View.INVISIBLE, layer.visibility)
        val suppressedGeneration = field<Long>(service, "generation")
        assertEquals(firstGeneration + 1, suppressedGeneration)
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals(saved.crop, field<PixelCrop>(service, "pageCrop"))
        assertSame(region, field<RectF>(service, "region"))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertEquals(suppressedGeneration, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
        assertEquals(View.VISIBLE, handle.visibility)
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals("ترجمة محفوظة", panel.translatedText.text.toString())
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals(saved.crop, field<PixelCrop>(service, "pageCrop"))
        assertFalse(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        assertNull(field<ReadingPageGuard>(service, "pageGuard").begin(saved.signature))
    }

    @Test fun pauseCancelsPendingWorkButResumeKeepsTheDisplayedPageAndDoesNotRecapture() = withWindows { service ->
        call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = "page")))
        val saved = completePage(service)
        val layer = field<ReadingTranslationView>(service, "pageView")
        val panel = field<ReadingOverlayControls>(service, "panel")
        val pending = Job()
        val staleTicket = field<ReadingPageGuard>(service, "pageGuard").begin(IntArray(64) { 140 })!!
        ReflectionHelpers.setField(service, "pageTicket", staleTicket)
        ReflectionHelpers.setField(service, "captureJob", pending)
        ReflectionHelpers.setField(service, "working", true)
        val generation = field<Long>(service, "generation")
        call(service, "togglePause")
        assertTrue(service.paused)
        assertTrue(pending.isCancelled)
        assertNull(field<ReadingPageGuard.Ticket?>(service, "pageTicket"))
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(staleTicket))
        assertFalse(field<Boolean>(service, "working"))
        assertNull(field<Job?>(service, "captureJob"))
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(saved.crop, field<PixelCrop>(service, "pageCrop"))
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals(generation + 1, field<Long>(service, "generation"))
        call(service, "togglePause")
        assertFalse(service.paused)
        assertNull(field<Job?>(service, "captureJob"))
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals("ترجمة محفوظة", panel.translatedText.text.toString())
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals(saved.crop, field<PixelCrop>(service, "pageCrop"))
        assertFalse(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        assertNull(field<ReadingPageGuard>(service, "pageGuard").begin(saved.signature))
    }

    @Test fun voiceFailureDoesNotDiscardThePageOrCompleteAnIndependentOcrRequest() = withWindows { service ->
        val saved = completePage(service)
        val guard = field<ReadingPageGuard>(service, "pageGuard")
        val pending = checkNotNull(guard.begin(IntArray(64) { 140 }))
        ReflectionHelpers.setField(service, "pageTicket", pending)
        ReflectionHelpers.setField(service, "working", true)
        call(service, "showVoiceError", ClassParameter.from(String::class.java, "Actual voice provider failure"))
        assertTrue(field<Boolean>(service, "working"))
        assertSame(pending, field<ReadingPageGuard.Ticket>(service, "pageTicket"))
        assertTrue(guard.isCurrent(pending))
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals("Actual voice provider failure", field<ReadingOverlayControls>(service, "panel").statusText.text.toString())
    }

    @Test fun openingSetupKeepsTheSessionAndItsSavedPageAlive() = withWindows { service ->
        val saved = completePage(service)
        val panel = field<ReadingOverlayControls>(service, "panel")
        descendants(panel).filterIsInstance<Button>().single { it.text == service.getString(R.string.reading_overlay_setup) }.performClick()
        val shadow = Shadows.shadowOf(service)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(ReadingStartActivity::class.java.name, shadow.nextStartedActivity.component?.className)
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertEquals(saved.crop, field<PixelCrop>(service, "pageCrop"))
        assertNull(field<Job?>(service, "captureJob"))
    }

    @Test fun motionMaskIncludesTheHandlesElevatedWindowShadowOutsideItsTouchBounds() = withWindows { service ->
        val handle=field<ReadingOverlayHandle>(service,"handle")
        handle.layout(0,0,48,48)
        handle.elevation=24f
        val size=ReflectionHelpers.callInstanceMethod<Pair<Int,Int>>(service,"screenSize")
        val crop=ReflectionHelpers.callInstanceMethod<PixelCrop>(service,"controlMotionCrop",
            ClassParameter.from(View::class.java,handle),
            ClassParameter.from(Int::class.javaPrimitiveType!!,size.first),
            ClassParameter.from(Int::class.javaPrimitiveType!!,size.second))
        val position=IntArray(2);handle.getLocationOnScreen(position)
        assertTrue(crop.left<=position[0])
        assertTrue(crop.top<=position[1])
        assertTrue("The sampled mask must include the renderer shadow beyond the layout width",crop.right>position[0]+handle.width)
        assertTrue(crop.bottom>position[1]+handle.height)
    }

    @Test @Config(sdk = [29, 31, 35])
    fun passthroughRetainsItsUnderlaySignalForBoxesAndReplacementWhileInteractiveRenderingIsUnfaded() = withWindows { service ->
        completePage(service)
        for (replacement in listOf(false,true)) for (passThrough in listOf(false,true)) {
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java,
                ReadingOverlaySettings(passThrough=passThrough,replaceText=replacement)))
            val params=field<WindowManager.LayoutParams>(service,"layerParams")
            if(passThrough)assertTrue(params.alpha>0f && params.alpha<=.8f)
            else assertEquals("Interactive windows must not fade already styled text and cleanup",1f,params.alpha,0f)
            if(passThrough && android.os.Build.VERSION.SDK_INT>=31)assertTrue(params.alpha<=
                service.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch)
            assertEquals(passThrough,params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
            val motion=ReadingMotion()
            val white=IntArray(ReadingMotion.WIDTH*ReadingMotion.HEIGHT) {Color.WHITE}
            val covered=listOf(PixelCrop(0,0,100,100))
            assertFalse(motion.observe(white,100,100,emptyList(),covered,paintRevision=1,overlayAlpha=params.alpha))
            // White overlay on a page turning from white to black. At full
            // opacity no underlay signal exists, so covered motion stays disabled.
            val grey=(255*params.alpha).toInt()
            val changed=IntArray(white.size) {Color.rgb(grey,grey,grey)}
            assertEquals(passThrough,motion.observe(changed,100,100,emptyList(),covered,
                paintRevision=1,overlayAlpha=params.alpha))
            assertEquals(passThrough,motion.lastDiagnostics.coveredEligible)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun repeatedProgressDoesNotResetTheActualPaintRevision() = withWindows { service ->
        val layer=field<ReadingTranslationView>(service,"pageView")
        layer.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY))
        layer.layout(0,0,100,100)
        completePage(service)
        val original=field<CameraOcrState>(service,"latest")
        val revision=field<Long>(service,"paintRevision")
        repeat(3) {
            call(service,"showRegions")
            call(service,"showTranslations",ClassParameter.from(List::class.java,original.boxTranslations))
        }
        assertEquals(revision,field<Long>(service,"paintRevision"))
        val box=original.boxTranslations.single()
        call(service,"showTranslations",ClassParameter.from(List::class.java,
            listOf(box.copy(line=box.line.copy(text="Different source metadata",confidence=.1f)))))
        assertEquals("Source text/confidence is not part of the displayed Arabic pixels",revision,field<Long>(service,"paintRevision"))
        call(service,"showTranslations",ClassParameter.from(List::class.java,listOf(box.copy(translation="قهوة جديدة"))))
        assertTrue(field<Long>(service,"paintRevision")>revision)
    }

    @Test fun hidingAndRestoringTheSamePaintKeepsItsReferenceButRequiresFreshComposition() = withWindows { service ->
        completePage(service)
        val view=field<ReadingTranslationView>(service,"pageView")
        val revision=field<Long>(service,"paintRevision")
        fun stable(generation: Long):Boolean=ReflectionHelpers.callInstanceMethod(service,"paintIsStable",
            ClassParameter.from(Long::class.javaPrimitiveType!!,generation))
        assertFalse(stable(1))
        view.viewTreeObserver.dispatchOnDraw()
        assertFalse("The compositor grace interval is part of admission",stable(1))
        ShadowSystemClock.advanceBy(Duration.ofMillis(151))
        assertFalse("A cached frame predating the draw cannot be a painted baseline",stable(0))
        assertTrue(stable(1))
        call(service,"showPanel")
        assertEquals(revision,field<Long>(service,"paintRevision"))
        assertFalse(stable(1))
        call(service,"resumeReader")
        assertEquals("Back restores the same pixels, preserving the last stable underlay reference",revision,field<Long>(service,"paintRevision"))
        assertFalse(stable(1))
        view.viewTreeObserver.dispatchOnDraw()
        ShadowSystemClock.advanceBy(Duration.ofMillis(151))
        assertTrue(stable(1))
    }

    @Test fun changingModesDuringTranslationClearsUnfinishedGeometryAndRearmsOnlyAutomaticMode() = withWindows { service ->
        val saved = completePage(service)
        for (mode in listOf("page", "manual")) {
            admitPage(service)
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = mode)))
            assertFalse(field<Boolean>(service, "working"))
            assertNull(field<ReadingPageGuard.Ticket?>(service, "pageTicket"))
            assertNull(field<PixelCrop?>(service, "pageCrop"))
            assertEquals(View.INVISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
            assertSame(saved.completed, field<Any>(service, "completedPage"))
            assertEquals(mode == "page", field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        }
    }

    @Test fun pausingUnfinishedAutomaticPageResumesWithCleanVerification() = withWindows { service ->
        val saved = completePage(service)
        call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = "page")))
        admitPage(service)
        call(service, "togglePause")
        assertTrue(service.paused)
        assertNull(field<PixelCrop?>(service, "pageCrop"))
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertFalse(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        call(service, "togglePause")
        assertFalse(service.paused)
        assertTrue(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        assertEquals(View.INVISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
    }

    @Test fun setupReturnKeepsThePreviousMotionBaselineSoChangedPagesAreDetected() = withWindows { service ->
        completePage(service)
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertTrue(field<ReadingMotion>(service, "motion").observe(
            IntArray(ReadingMotion.WIDTH*ReadingMotion.HEIGHT) {Color.WHITE},100,100,emptyList()))
        assertEquals(View.VISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
    }

    @Test fun uncertainManualSetupReturnRemovesOldGeometryUntilExplicitRefresh() = withWindows { service ->
        val saved = completePage(service)
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(textSizeSp = 22f)))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertEquals(View.INVISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
        assertNull(field<PixelCrop?>(service, "pageCrop"))
        assertSame("History remains available, but old geometry cannot cover a changed reader", saved.completed, field<Any>(service, "completedPage"))
        assertFalse(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        assertNull(field<Job?>(service, "captureJob"))
    }

    @Test fun uncertainAutomaticSetupReturnSchedulesCleanDedupeVerification() = withWindows { service ->
        val saved = completePage(service)
        call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = "page")))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(mode = "page", showOriginal = true)))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertNull(field<PixelCrop?>(service, "pageCrop"))
        assertTrue(field<ReadingTrigger>(service, "trigger").ready(Long.MAX_VALUE))
        assertNull("An unchanged clean page must still reuse its previous result", field<ReadingPageGuard>(service, "pageGuard").begin(saved.signature))
        assertSame(saved.completed, field<Any>(service, "completedPage"))
    }

    @Test fun setupReturnDoesNotRestoreCancelledUnfinishedHighlights() = withWindows { service ->
        completePage(service)
        admitPage(service)
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertFalse(field<Boolean>(service, "working"))
        assertNull(field<PixelCrop?>(service, "pageCrop"))
        assertEquals(View.INVISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
    }

    @Test fun submissionUsesActualWorkerLifecycleWhenTheObservedStateIsBehind() = withWindows { service ->
        val preferences = OcrPreferences(service)
        val previous = preferences.read()
        preferences.update { OcrSelection(translate = false) }
        var reads = 0
        val reader = object : OcrEngine {
            override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int): List<OcrLine> {
                reads++;return listOf(OcrLine(5,5,90,20,"A coffee article",1f))
            }
            override fun close() {}
            override fun cancel() {}
        }
        val actual = CameraOcrController({reader},{error("Translation is disabled")},{AutoCloseable {}},
            Dispatchers.Unconfined,Dispatchers.Unconfined,Dispatchers.Unconfined)
        field<CameraOcrController>(service,"controller").close()
        ReflectionHelpers.setField(service,"controller",actual)
        ReflectionHelpers.setField(service,"latest",CameraOcrState(running=true,closing=true))
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        try {
            runBlocking {
                suspendCoroutine<Unit> {continuation ->
                    try {
                        val method=ReadingOverlayService::class.java.getDeclaredMethod("submit",Bitmap::class.java,
                            Boolean::class.javaPrimitiveType!!,Continuation::class.java).apply {isAccessible=true}
                        if(method.invoke(service,bitmap,true,continuation)!==COROUTINE_SUSPENDED)continuation.resume(Unit)
                    }catch(failure:InvocationTargetException){continuation.resumeWithException(failure.targetException)}
                }
            }
            assertEquals("A stale UI running flag must not send the frame to a closed queue",1,reads)
            assertEquals("A coffee article",actual.state.value.text)
            call(service,"onOcrState",ClassParameter.from(CameraOcrState::class.java,actual.state.value))
            assertFalse(field<Boolean>(service,"working"))
            assertNull(field<ReadingPageGuard.Ticket?>(service,"pageTicket"))
        } finally {preferences.update {previous};actual.close();if(!bitmap.isRecycled)bitmap.recycle()}
    }

    @Test fun redrawingAnAreaAdmitsTheWholeImageInsteadOfThePreviousSelectedCrop() = withWindows { service ->
        ReflectionHelpers.setField(service, "region", RectF(.2f, .3f, .7f, .8f))
        fun crop(useSelectedArea: Boolean): PixelCrop = ReflectionHelpers.callInstanceMethod(service, "captureCrop",
            ClassParameter.from(Int::class.javaPrimitiveType!!, 100),
            ClassParameter.from(Int::class.javaPrimitiveType!!, 200),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, useSelectedArea))
        val selected = crop(true)
        val redraw = crop(false)
        assertNotEquals(selected, redraw)
        ReflectionHelpers.setField(service, "region", null)
        assertEquals("Selecting a replacement area must inspect the normal whole-page capture", crop(true), redraw)
    }

    @Test fun successfulManualOriginalOnlyResultNeverOpensTheOptions() = withWindows { service ->
        val ticket = admitPage(service)
        val value = CameraOcrState(running = true, text = "Coffee comes from roasted seeds")
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, value))
        val panel = field<ReadingOverlayControls>(service, "panel")
        assertEquals(View.GONE, panel.visibility)
        assertEquals(View.VISIBLE, field<ReadingOverlayHandle>(service, "handle").visibility)
        assertEquals(value.text, panel.originalText.text.toString())
        assertFalse(field<Boolean>(service, "working"))
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        assertNotNull(field<Any?>(service, "completedPage"))
        assertNull(field<String?>(service, "sessionError"))
    }

    @Test fun intermediateTranslationErrorKeepsPageOwnershipUntilFinalSuccessfulTranslation() = withWindows { service ->
        val ticket = admitPage(service)
        val line = OcrLine(10, 10, 90, 30, "Fresh coffee", 1f)
        val panel = field<ReadingOverlayControls>(service, "panel")
        val partial = CameraOcrState(running = true, processing = true, text = line.text,
            lines = listOf(line), error = "Intermediate translator request failed")
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, partial))
        assertTrue(field<Boolean>(service, "working"))
        assertSame(ticket, field<ReadingPageGuard.Ticket>(service, "pageTicket"))
        assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        assertEquals(View.GONE, panel.visibility)
        assertEquals(partial.error, field<String?>(service, "sessionError"))
        val translating = partial.copy(processing = false, translating = true, error = null)
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, translating))
        assertTrue(field<Boolean>(service, "working"))
        assertNull(field<String?>(service, "sessionError"))
        val completed = translating.copy(translating = false, translation = "قهوة طازجة",
            boxTranslations = listOf(TranslatedOcrBox(line, "قهوة طازجة")))
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, completed))
        assertFalse(field<Boolean>(service, "working"))
        assertNull(field<ReadingPageGuard.Ticket?>(service, "pageTicket"))
        assertEquals(completed.translation, panel.translatedText.text.toString())
        assertEquals(View.VISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
        assertEquals(View.GONE, panel.visibility)
        assertNotNull(field<Any?>(service, "completedPage"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun progressiveOcrInvalidationRemovesStaleLabelsAndCleanupEvenIfReplacementFails() = withWindows { service ->
        call(service,"applyOptions",ClassParameter.from(ReadingOverlaySettings::class.java,ReadingOverlaySettings(replaceText=true)))
        val ticket = admitPage(service)
        val paint = MangaPaint(ComicMask(intArrayOf(10, 10, 20), 100, 100),
            OcrRect(10, 10, 20, 1), Color.WHITE, Color.BLACK)
        val region = OcrRegion(OcrRect(10, 10, 90, 30), 1f, paint = paint)
        val original = OcrLine(10, 10, 90, 30, "Coffee", 1f, region)
        val partial = CameraOcrState(running = true, processing = true, text = original.text,
            translation = "قهوة", lines = listOf(original), regions = listOf(region),
            boxTranslations = listOf(TranslatedOcrBox(original, "قهوة")))
        val layer = field<ReadingTranslationView>(service, "pageView")
        layer.layout(0, 0, 100, 100)
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, partial))
        assertEquals(1, layer.childCount)
        val cleanups = ReflectionHelpers.getField<Map<*, *>>(layer, "cleanupImages")
        assertEquals(1, cleanups.size)
        val cleanupImage = ReflectionHelpers.getField<Bitmap>(cleanups.values.single(), "image")
        val panel = field<ReadingOverlayControls>(service, "panel")
        assertEquals("قهوة", panel.translatedText.text.toString())

        // The same admitted page now has corrected OCR text. Exact-box matching
        // drops its previous translation before the replacement model call ends.
        val corrected = original.copy(text = "Coffee roasting")
        val pending = partial.copy(text = corrected.text, translation = "", lines = listOf(corrected),
            boxTranslations = emptyList(), translating = true)
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, pending))
        assertTrue(field<List<TranslatedOcrBox>>(service, "renderedBoxes").isEmpty())
        assertEquals(0, layer.childCount)
        assertTrue(cleanups.isEmpty())
        assertTrue(cleanupImage.isRecycled)
        assertEquals("", panel.translatedText.text.toString())
        assertEquals(listOf(region), field<List<OcrRegion>>(service, "renderedRegions"))
        assertTrue(field<Boolean>(service, "working"))
        assertSame(ticket, field<ReadingPageGuard.Ticket>(service, "pageTicket"))
        assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        assertEquals(View.GONE, field<ReadingOverlayControls>(service, "panel").visibility)

        val failed = pending.copy(processing = false, translating = false,
            error = "Actual Marian error: translation returned empty text")
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, failed))
        assertEquals(0, layer.childCount)
        assertTrue(field<List<TranslatedOcrBox>>(service, "renderedBoxes").isEmpty())
        assertEquals(failed.error, field<String?>(service, "sessionError"))
        assertEquals("", panel.translatedText.text.toString())
        assertFalse(field<Boolean>(service, "working"))
        assertNotNull(field<Any?>(service, "completedPage"))
        assertEquals(View.GONE, field<ReadingOverlayControls>(service, "panel").visibility)
    }

    @Test fun retiringWorkerCannotCompleteAPageThatIsStillWaitingToBeSubmitted() = withWindows { service ->
        val ticket = admitPage(service)
        ReflectionHelpers.setField(service, "frameSubmitted", false)
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java,
            CameraOcrState(text = "Old worker text", translation = "Old worker output")))
        assertTrue(field<Boolean>(service, "working"))
        assertSame(ticket, field<ReadingPageGuard.Ticket>(service, "pageTicket"))
        assertEquals("", field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
        assertNull(field<Any?>(service, "completedPage"))
    }

    @Test fun manualSnapshotFinishesThroughAVideoAndRemainsReadableUntilTheNextTap() = withWindows { service ->
        val ticket = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", true)
        for (kind in ReadingMotion.ChangeKind.entries) {
            call(service, "onPageMotion", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
                ClassParameter.from(ReadingMotion.ChangeKind::class.java, kind))
            assertTrue(field<Boolean>(service, "working"))
            assertSame(ticket, field<ReadingPageGuard.Ticket>(service, "pageTicket"))
            assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        }
        val line = OcrLine(5, 5, 80, 20, "Coffee in the video", 1f)
        val result = CameraOcrState(text = line.text, translation = "القهوة في الفيديو",
            boxTranslations = listOf(TranslatedOcrBox(line, "القهوة في الفيديو")))
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, result))
        assertFalse(field<Boolean>(service, "working"))
        assertNull(field<ReadingPageGuard.Ticket?>(service, "pageTicket"))
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        val completed = field<Any>(service, "completedPage")
        repeat(3) { widespreadMotion(service) }
        assertSame(completed, field<Any>(service, "completedPage"))
        assertEquals(result.translation, field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
        assertEquals(View.VISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
    }

    @Test fun videoMovementStillInvalidatesAnAutomaticTicketAndItsLateResult() = withWindows { service ->
        val ticket = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", false)
        widespreadMotion(service)
        assertFalse(field<Boolean>(service, "working"))
        assertNull(field<ReadingPageGuard.Ticket?>(service, "pageTicket"))
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java,
            CameraOcrState(text = "Obsolete video frame", translation = "Obsolete translation")))
        assertNull(field<Any?>(service, "completedPage"))
        assertEquals("", field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
    }

    @Test fun pageModeResumesMotionFollowingAfterAnExplicitSnapshotFinishes() = withWindows { service ->
        call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java,
            ReadingOverlaySettings(mode = "page")))
        val ticket = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", true)
        widespreadMotion(service)
        assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java,
            CameraOcrState(text = "A captured frame", translation = "لقطة")))
        assertNotNull(field<PixelCrop?>(service, "pageCrop"))
        widespreadMotion(service)
        assertNull(field<PixelCrop?>(service, "pageCrop"))
        assertEquals("", field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
    }

    @Test fun manualSnapshotAllowsTheRealTranslationWorkerToPublishWhileVideoMoves() = withWindows { service ->
        val pending = CompletableDeferred<String>()
        var translations = 0
        val translator = object : CancellableTextTranslator {
            override val id = "snapshot-test"
            override val directions = setOf(TranslationDirection("en", "ar"))
            override suspend fun translate(text: String, direction: TranslationDirection): String {
                translations++;return pending.await()
            }
            override fun close() = Unit
            override fun cancel() = Unit
        }
        val reader = object : OcrEngine {
            override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) =
                listOf(OcrLine(5, 5, 80, 20, "Coffee on a moving video", 1f))
            override fun close() = Unit
            override fun cancel() = Unit
        }
        val actual = CameraOcrController({ reader }, { translator }, { AutoCloseable {} },
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        field<CameraOcrController>(service, "controller").close()
        ReflectionHelpers.setField(service, "controller", actual)
        val ticket = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", true)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        actual.start(OcrCatalog.profile("latin"), "en", "ar", ConversationTranslatorSnapshot("test"),
            live = false, initial = bitmap, positioned = true)
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            actual.state.collect { call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, it)) }
        }
        try {
            assertEquals(1, translations)
            assertTrue(actual.state.value.translating)
            repeat(4) { widespreadMotion(service) }
            assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(ticket))
            pending.complete("القهوة في فيديو متحرك")
            assertFalse(field<Boolean>(service, "working"))
            assertEquals("القهوة في فيديو متحرك", field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
            assertNotNull(field<Any?>(service, "completedPage"))
            widespreadMotion(service)
            assertEquals(View.VISIBLE, field<ReadingTranslationView>(service, "pageView").visibility)
        } finally { observer.cancel();actual.close();if(!bitmap.isRecycled)bitmap.recycle() }
    }

    @Test fun setupAndNewerManualTicketsStillRetireTheOldSnapshot() = withWindows { service ->
        val first = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", true)
        call(service, "cancelAttempt")
        val second = admitPage(service)
        ReflectionHelpers.setField(service, "manualPage", true)
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(first))
        assertTrue(field<ReadingPageGuard>(service, "pageGuard").isCurrent(second))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        assertFalse(field<ReadingPageGuard>(service, "pageGuard").isCurrent(second))
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java,
            CameraOcrState(text = "Hidden page", translation = "Late hidden output")))
        call(service, "suppressForSetup", ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        assertEquals("", field<ReadingOverlayControls>(service, "panel").translatedText.text.toString())
        assertNull(field<Any?>(service, "completedPage"))
        assertFalse(field<Boolean>(service, "manualPage"))
    }

    private fun widespreadMotion(service: ReadingOverlayService) = call(service, "onPageMotion",
        ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        ClassParameter.from(ReadingMotion.ChangeKind::class.java, ReadingMotion.ChangeKind.WIDESPREAD))

    @Test fun ignoredTickerDoesNotChangeCleanPageSignatureOrAlterCapturedPixels() = withWindows { service ->
        val first = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val second = first.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 10 until 25) for (x in 0 until 100) second.setPixel(x,y,Color.BLACK)
        val ignored = listOf(PixelCrop(0,10,100,25))
        fun signature(image: Bitmap, areas: List<PixelCrop>) = ReflectionHelpers.callInstanceMethod<IntArray>(service, "signature",
            ClassParameter.from(Bitmap::class.java, image), ClassParameter.from(List::class.java, areas))
        try {
            assertFalse(signature(first,emptyList()).contentEquals(signature(second,emptyList())))
            assertArrayEquals(signature(first,ignored),signature(second,ignored))
            assertEquals(Color.BLACK,second.getPixel(50,15))
            assertEquals(Color.WHITE,first.getPixel(50,15))
        } finally { first.recycle();second.recycle() }
    }

    @Test fun ignoredAreaSelectorPersistsItsCaptureGeometryAndLeavesSourcePixelsUntouched() = withWindows { service ->
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        call(service,"selectRegion",ClassParameter.from(Bitmap::class.java,image),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!,true))
        val selector = field<LinearLayout>(service,"selector")
        val area = selector.getChildAt(2)
        area.layout(0,0,100,100)
        ReflectionHelpers.callInstanceMethod<Unit>(area,"chooseCenter")
        val saveText = service.getString(com.sal7one.transiber.R.string.reading_exclusions_save)
        descendants(selector).filterIsInstance<Button>().single {it.text.toString()==saveText}.performClick()
        assertNull(field<LinearLayout?>(service,"selector"))
        assertTrue(image.isRecycled)
        val saved = ReadingOverlayPreferences(service).read().motionExclusions.single()
        assertEquals(PixelCrop(20,20,80,80),saved.cropFor(100,100))
        assertNull(saved.cropFor(200,100))
        val active = ReflectionHelpers.callInstanceMethod<List<PixelCrop>>(service,"ignoredAreas",
            ClassParameter.from(Int::class.javaPrimitiveType!!,100),ClassParameter.from(Int::class.javaPrimitiveType!!,100))
        assertEquals(listOf(PixelCrop(20,20,80,80)),active)
    }

    @Test fun terminalTranslationErrorKeepsOriginalAndPartialOutputWithoutStealingFocus() = withWindows { service ->
        admitPage(service)
        val line = OcrLine(10, 10, 90, 30, "Coffee", 1f)
        val failed = CameraOcrState(running = true, text = "Coffee\nRoasting", translation = "قهوة",
            lines = listOf(line), boxTranslations = listOf(TranslatedOcrBox(line, "قهوة")),
            error = "Actual provider error: unavailable model")
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java, failed))
        val panel = field<ReadingOverlayControls>(service, "panel")
        assertEquals(View.GONE, panel.visibility)
        assertFalse(field<Boolean>(service, "working"))
        assertNotNull(field<Any?>(service, "completedPage"))
        assertEquals(failed.text, panel.originalText.text.toString())
        assertEquals(failed.translation, panel.translatedText.text.toString())
        assertEquals(failed.error, field<String?>(service, "sessionError"))
        val handle = field<ReadingOverlayHandle>(service, "handle")
        assertTrue(descendants(handle).filterIsInstance<Button>().any {
            it.isEnabled && it.contentDescription?.contains(failed.error!!) == true
        })
        call(service, "showPanel")
        assertEquals(failed.error, panel.statusText.text.toString())
    }

    @Test fun translateTapPreemptsPassiveCaptureAndWaitsForItsCleanup() = withWindows { service ->
        ReflectionHelpers.setField(service, "projection", ReflectionHelpers.newInstance(MediaProjection::class.java))
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        val previous = CoroutineScope(Dispatchers.Unconfined).launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await(); cleaned = true } }
        }
        ReflectionHelpers.setField(service, "captureJob", previous)
        service.requestCapture(false)
        val replacement = field<Job>(service, "captureJob")
        assertNotSame("The explicit tap must replace the passive capture", previous, replacement)
        assertTrue(previous.isCancelled)
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(cleaned)
        assertTrue("The replacement must wait while the previous frame listener releases", replacement.isActive)
        assertTrue(field<Boolean>(service, "working"))
        assertNull(field<String?>(service, "sessionError"))
        // Cancel before any real capture/configuration is attempted by this host test.
        call(service, "cancelAttempt")
        releaseCleanup.complete(Unit)
        assertTrue(cleaned)
        assertTrue(previous.isCompleted)
        assertTrue(replacement.isCancelled)
        assertFalse(field<Boolean>(service, "working"))
    }

    @Test fun configurationErrorCanBeRepairedWithinTheSameService() = withWindows { service ->
        val preferences = OcrPreferences(service)
        val previous = preferences.read()
        try {
            preferences.update { OcrSelection(source = "not-a-language", translate = false) }
            try {
                prepareSession(service)
                fail("Unsupported language must surface its error")
            } catch (failure: IllegalStateException) {
                call(service, "showError", ClassParameter.from(String::class.java, checkNotNull(failure.message)))
            }
            assertNotNull(field<String?>(service, "sessionError"))
            assertFalse(field<Boolean>(service, "setupReady"))
            preferences.update { OcrSelection(source = "en", target = "ar", translate = false) }
            prepareSession(service)
            assertTrue(field<Boolean>(service, "setupReady"))
            assertNull(field<String?>(service, "setupError"))
            assertEquals("en", field<String>(service, "source"))
            assertEquals("ar", field<String>(service, "target"))
            assertNull(field<Any?>(service, "snapshot"))
        } finally { preferences.update { previous } }
    }

    @Test fun staleCompletionAfterPauseCannotOpenControlsOrReplaceSavedText() = withWindows { service ->
        val saved = completePage(service)
        admitPage(service)
        call(service, "togglePause")
        call(service, "onOcrState", ClassParameter.from(CameraOcrState::class.java,
            CameraOcrState(text = "Late OCR", translation = "Late translation", error = "Late provider error")))
        assertEquals(View.GONE, field<ReadingOverlayControls>(service, "panel").visibility)
        assertSame(saved.completed, field<Any>(service, "completedPage"))
        assertNull(field<String?>(service, "sessionError"))
    }

    private fun admitPage(service: ReadingOverlayService): ReadingPageGuard.Ticket {
        ReflectionHelpers.setField(service, "profile", OcrCatalog.profile("latin"))
        ReflectionHelpers.setField(service, "pageWidth", 100)
        ReflectionHelpers.setField(service, "pageHeight", 100)
        ReflectionHelpers.setField(service, "pageCrop", PixelCrop(0, 0, 100, 100))
        val ticket = checkNotNull(field<ReadingPageGuard>(service, "pageGuard").begin(IntArray(64) { 140 }, manual = true))
        ReflectionHelpers.setField(service, "pageTicket", ticket)
        ReflectionHelpers.setField(service, "working", true)
        ReflectionHelpers.setField(service, "frameSubmitted", true)
        return ticket
    }

    private fun prepareSession(service: ReadingOverlayService) = runBlocking {
        suspendCoroutine<Unit> { continuation ->
            try {
                val method = ReadingOverlayService::class.java.getDeclaredMethod("prepareSession", Continuation::class.java)
                method.isAccessible = true
                if (method.invoke(service, continuation) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (failure: InvocationTargetException) { continuation.resumeWithException(failure.targetException) }
        }
    }

    private data class SavedPage(val completed: Any, val crop: PixelCrop, val signature: IntArray)
    private fun completePage(service: ReadingOverlayService): SavedPage {
        val crop = PixelCrop(0, 0, 100, 100)
        val signature = IntArray(64) { 80 }
        val value = CameraOcrState(text = "Saved original", translation = "ترجمة محفوظة",
            boxTranslations = listOf(TranslatedOcrBox(OcrLine(10, 10, 40, 20, "Saved original", 1f), "ترجمة محفوظة")))
        ReflectionHelpers.setField(service, "setupReady", true)
        field<ReadingMotion>(service, "motion").observe(IntArray(ReadingMotion.WIDTH*ReadingMotion.HEIGHT) {Color.rgb(80,80,80)},100,100,emptyList())
        ReflectionHelpers.setField(service, "motionBaselineReady", true)
        ReflectionHelpers.setField(service, "pageWidth", 100)
        ReflectionHelpers.setField(service, "pageHeight", 100)
        ReflectionHelpers.setField(service, "pageCrop", crop)
        ReflectionHelpers.setField(service, "latest", value)
        ReflectionHelpers.setField(service, "pageTicket", field<ReadingPageGuard>(service, "pageGuard").begin(signature))
        call(service, "finishAttempt", ClassParameter.from(CameraOcrState::class.java, value))
        call(service, "restoreCompletedPage")
        return SavedPage(field(service, "completedPage"), crop, signature)
    }

    private fun withWindows(block: (ReadingOverlayService) -> Unit) {
        val controller: ServiceController<ReadingOverlayService> = Robolectric.buildService(ReadingOverlayService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(UserPreferences.store(service, "reading-overlay").edit().clear().commit())
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("reading-overlay", "Screen translation", NotificationManager.IMPORTANCE_LOW))
            val settings = ReadingOverlaySettings()
            ReadingOverlayPreferences(service).write(settings)
            call(service, "configureOptions", ClassParameter.from(ReadingOverlaySettings::class.java, settings))
            call(service, "createWindows")
            block(service)
        } finally {
            ReflectionHelpers.setField(service, "projection", null)
            controller.destroy()
        }
    }

    private fun call(service: ReadingOverlayService, method: String, vararg args: ClassParameter<*>) =
        ReflectionHelpers.callInstanceMethod<Unit>(service, method, *args)
    private fun <T> field(service: ReadingOverlayService, name: String): T = ReflectionHelpers.getField(service, name)
    private fun descendants(root: ViewGroup): List<View> = buildList {
        repeat(root.childCount) { index ->
            val view = root.getChildAt(index)
            add(view)
            if (view is ViewGroup) addAll(descendants(view))
        }
    }
}
