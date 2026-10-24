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
import android.widget.Switch
import com.sal7one.transiber.R
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.transiber.ocr.TranslatedOcrBox
import com.sal7one.transiber.ocr.PixelCrop
import com.sal7one.transiber.ocr.CameraOcrController
import com.sal7one.transiber.ocr.CameraOcrState
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrPreferences
import com.sal7one.transiber.ocr.OcrSelection
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.*
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
        assertEquals(View.VISIBLE, layer.visibility)
        assertEquals(generation, field<Long>(service, "generation"))
        assertNull(field<Job?>(service, "captureJob"))
        assertEquals(preferences.read(), ReadingOverlayPreferences(service).read())
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

    @Test fun interactiveAndPassthroughLayersBothLeaveAnObservableUnderlaySignal() = withWindows { service ->
        completePage(service)
        for (passThrough in listOf(false,true)) {
            call(service, "applyOptions", ClassParameter.from(ReadingOverlaySettings::class.java, ReadingOverlaySettings(passThrough=passThrough)))
            val params=field<WindowManager.LayoutParams>(service,"layerParams")
            assertTrue(params.alpha>0f && params.alpha<=.8f)
            assertEquals(passThrough,params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
        }
    }

    @Test fun repeatedProgressDoesNotResetTheActualPaintRevision() = withWindows { service ->
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
