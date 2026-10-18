package com.sal7one.transiber.caption

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SpeechAccelerationStoreTest {
    @Test fun engineChoicesPersistIndependentlyAndRejectUnsupportedBackend() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SpeechAccelerationStore.save(context, CaptionEngineChoice.WHISPER, "cpu")
        SpeechAccelerationStore.save(context, CaptionEngineChoice.TRANSCRIBEPSY, "opencl")
        assertEquals("cpu", SpeechAccelerationStore.current(context, CaptionEngineChoice.WHISPER))
        assertEquals("opencl", SpeechAccelerationStore.current(context, CaptionEngineChoice.TRANSCRIBEPSY))
        try {
            SpeechAccelerationStore.save(context, CaptionEngineChoice.VOSK, "vulkan")
            fail("CPU-only engines must reject unsupported acceleration")
        } catch (_: IllegalArgumentException) { }
        assertEquals("cpu", SpeechAccelerationStore.current(context, CaptionEngineChoice.VOSK))
        SpeechAccelerationStore.save(context, CaptionEngineChoice.TRANSCRIBEPSY, "cpu")
    }
}
