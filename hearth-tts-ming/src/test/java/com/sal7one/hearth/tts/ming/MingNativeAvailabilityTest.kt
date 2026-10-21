package com.sal7one.hearth.tts.ming

import com.sal7one.common_jni.MingNative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host JVM is not an arm64 Android process: availability must degrade, never throw. */
class MingNativeAvailabilityTest {
    @Test fun hostReportsUnavailableWithItsRealReasonInsteadOfCrashingClassInit() {
        val availability = MingNative.availability
        assertFalse(availability.loaded)
        assertTrue(availability.reason.isNotBlank())
        assertFalse(MingNative.availability.library != null)
    }

    @Test fun engineAvailabilityUsesTheSameLoaderState() {
        assertFalse(MingSpeechEngine.available())
        assertTrue(MingSpeechEngine.availabilityReason().isNotBlank())
    }
}
