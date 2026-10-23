package com.sal7one.transiber.reading

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.RectF
import android.media.projection.MediaProjection
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Switch
import com.sal7one.transiber.R
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.transiber.ocr.TranslatedOcrBox
import com.sal7one.transiber.ocr.PixelCrop
import com.sal7one.transiber.ocr.CameraOcrState
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
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

    private data class SavedPage(val completed: Any, val crop: PixelCrop, val signature: IntArray)
    private fun completePage(service: ReadingOverlayService): SavedPage {
        val crop = PixelCrop(0, 0, 100, 100)
        val signature = IntArray(64) { 80 }
        val value = CameraOcrState(text = "Saved original", translation = "ترجمة محفوظة",
            boxTranslations = listOf(TranslatedOcrBox(OcrLine(10, 10, 40, 20, "Saved original", 1f), "ترجمة محفوظة")))
        ReflectionHelpers.setField(service, "setupReady", true)
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
