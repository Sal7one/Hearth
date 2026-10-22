package com.sal7one.hearth.tts.supertonic

import com.sal7one.common_jni.voice.SupertonicVoice
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A host JVM has no Android TTS JNI, just like an APK built for an unsupported ABI. */
class SupertonicAvailabilityTest {
    @Test fun probingMissingNativeRuntimeNeverPoisonsClassInitialization() {
        repeat(2) {
            assertFalse(SupertonicVoice.available())
            assertFalse(SupertonicSpeechEngine.available())
            assertTrue(SupertonicSpeechEngine.availabilityReason().contains("Supertonic native runtime is unavailable"))
            assertEquals(SupertonicVoice.availabilityReason(), SupertonicSpeechEngine.availabilityReason())
        }
    }

    @Test fun constructionReportsActualLoaderFailureInsteadOfLinkageError() {
        val error = assertThrows(IllegalStateException::class.java) { SupertonicVoice(File("not-installed")) }
        assertEquals(SupertonicVoice.availabilityReason(), error.message)
    }

    @Test fun synthesisRejectsMissingRuntimeBeforeModelWorkAndNeverEmitsPcm() {
        val engine = SupertonicSpeechEngine(File("not-installed"))
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                engine.synthesize("Hello", "en", engine.voices.first(), SpeechOptions(), SpeechCancellation()) {
                    throw AssertionError("Unavailable runtime emitted PCM")
                }
            }
            assertEquals(SupertonicSpeechEngine.availabilityReason(), error.message)
        } finally {
            engine.close()
        }
    }
}
