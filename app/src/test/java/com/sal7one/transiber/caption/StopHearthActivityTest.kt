package com.sal7one.transiber.caption

import android.app.Application
import com.sal7one.transiber.reading.ReadingOverlayService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class StopHearthActivityTest {
    @Test fun emergencyLauncherFinishesWithoutStartingEnginesOrOpeningPermissionUiEvenWhenRepeated() {
        repeat(2) {
            val controller = Robolectric.buildActivity(StopHearthActivity::class.java).create()
            val activity = controller.get()
            val shadow = shadowOf(activity)
            assertTrue(activity.isFinishing)
            assertTrue(shadow.allStartedServices.isEmpty())
            assertNull(shadow.nextStartedActivity)
            val stopped = generateSequence { shadow.nextStoppedService }
                .map { it.component!!.className }.toSet()
            assertEquals(setOf(CaptionCaptureService::class.java.name, ReadingOverlayService::class.java.name), stopped)
            controller.destroy()
        }
    }
}
