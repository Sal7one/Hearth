package com.sal7one.transiber.caption

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the real failure handler without starting capture, a model or a provider client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CaptionFailurePlacementTest {
    @Test fun providerFailurePreservesLiveAndPersistedPlacementAndStillSurfacesTheError() = runBlocking {
        val controller = Robolectric.buildService(CaptionCaptureService::class.java).create()
        val service = controller.get()
        val overlay = ReflectionHelpers.getField<CaptionOverlayController>(service, "overlay")
        val live = ReflectionHelpers.getField<MutableStateFlow<CaptionOverlayConfig>>(overlay, "_config")
        try {
            for (anchor in listOf(CaptionAnchor.BOTTOM, CaptionAnchor.TOP)) {
                val saved = CaptionOverlayConfig(anchor = anchor, xOffsetPx = 34, yOffsetPx = 52,
                    widthPercent = 70, bubbleHeightDp = 180, theme = CaptionTheme.LIGHT)
                CaptionConfigStore.update(service) { saved }
                live.value = CaptionConfigStore.config(service).first()
                val expected = live.value
                ReflectionHelpers.setField(service, "active", true)
                ReflectionHelpers.setField(service, "failure", null)
                ReflectionHelpers.callInstanceMethod<Unit>(service, "fail",
                    ReflectionHelpers.ClassParameter.from(String::class.java, "Provider rejected the test credential"))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(expected, overlay.config.value)
                assertEquals(expected, CaptionConfigStore.config(service).first())
                assertEquals("Provider rejected the test credential", CaptionCaptureService.session.value.error)
                assertTrue(CaptionCaptureService.session.value.overlayVisible)
            }
        } finally {
            controller.destroy()
        }
    }
}
