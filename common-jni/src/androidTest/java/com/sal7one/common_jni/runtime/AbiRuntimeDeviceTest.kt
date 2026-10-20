package com.sal7one.common_jni.runtime

import android.os.Process
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.marian.MarianNative
import com.sal7one.common_jni.speech.SpeechBackend
import com.sal7one.common_jni.speech.SpeechRuntime
import com.sal7one.common_jni.translation.LocalTranslationNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No model files/providers: exercise actual Android loading and versioned ABI discovery. */
@RunWith(AndroidJUnit4::class)
class AbiRuntimeDeviceTest {
    @Test fun packagedNativeEnginesLoadInTheSelectedProcessArchitecture() {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedAbi = requireNotNull(arguments.getString("expectedAbi")) { "Select expectedAbi explicitly" }
        require(expectedAbi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) { "Unknown expectedAbi: $expectedAbi" }
        assertTrue("Guest does not advertise $expectedAbi", expectedAbi in Build.SUPPORTED_ABIS)
        assertEquals("Selected ABI process width", expectedAbi in setOf("arm64-v8a", "x86_64"), Process.is64Bit())
        arguments.getString("expectedBits")?.let { assertEquals(it == "64", Process.is64Bit()) }
        CommonJni.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Whisper missing", CommonJni.hasWhisper())
        assertTrue("Vosk missing", CommonJni.hasVosk())
        checkVoskCapiLoads()
        assertTrue("ONNX missing", CommonJni.hasOnnx())
        assertTrue("Marian runtime missing", MarianNative.nativeRuntimeAvailable())
        // Version 3 is required by LocalTranslationSession for the current prompt contracts.
        assertEquals("Translation JNI prompt protocol", 3, LocalTranslationNative.promptProtocolVersion())
        val allAbiBackends = setOf(SpeechBackend.MOONSHINE, SpeechBackend.QWEN3_ASR,
            SpeechBackend.OMNILINGUAL_CTC, SpeechBackend.PHONON, SpeechBackend.TRANSCRIBEPSY, SpeechBackend.NEMOTRON_3_5)
        val arm64Backends = mapOf(SpeechBackend.COHERE to "libhearth_transcribe_cpp.so",
            SpeechBackend.FUNASR_MLT to "libhearth_transcribe_cpp.so", SpeechBackend.VOXTRAL_REALTIME to "libhearth_transcribe_cpp.so",
            SpeechBackend.AUDAR to "libhearth_audar.so", SpeechBackend.OWSM to "libhearth_owsm.so")
        assertEquals("Every speech backend needs an explicit APK architecture contract",
            SpeechBackend.entries.toSet(), allAbiBackends + arm64Backends.keys)
        for (backend in SpeechBackend.entries) {
            val availability = SpeechRuntime().availability(backend)
            val expectedAvailable = backend in allAbiBackends || expectedAbi == "arm64-v8a"
            assertEquals("$expectedAbi ${backend.id}: ${availability.error}", expectedAvailable, availability.available)
            if (expectedAvailable) {
                assertFalse("${backend.id}: revision missing", availability.revision.isNullOrBlank())
                assertNull("${backend.id}: available backend reported an error", availability.error)
            } else {
                assertNull("${backend.id}: unavailable backend reported a revision", availability.revision)
                assertFalse("${backend.id}: missing unavailable-runtime error", availability.error.isNullOrBlank())
                assertTrue("${backend.id}: error must identify its absent runtime: ${availability.error}",
                    availability.error!!.contains(arm64Backends.getValue(backend)))
            }
        }
        Log.i("HearthAbiSmoke", "native libraries and speech ABI availability contracts PASS; abi=$expectedAbi; processBits=${if (Process.is64Bit()) 64 else 32}")
    }

    private fun checkVoskCapiLoads() {
        // hasVosk() is a compile-time feature flag. Exercise the real RTLD_LOCAL
        // loader without weights: it resolves every C symbol before rejecting
        // the empty model path. Do not globally load Vosk's legacy C++ runtime.
        val engine = VoskEngine()
        fun method(name: String, vararg types: Class<*>) =
            VoskEngine::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        val handle = method("nativeCreateVosk").invoke(engine) as Long
        assertNotEquals(0L, handle)
        try {
            val loaded = method("nativeInitVosk", java.lang.Long.TYPE, String::class.java, String::class.java)
                .invoke(engine, handle, "", "{}") as Boolean
            assertFalse("An empty model path must never initialize successfully", loaded)
            assertEquals("Vosk C API must resolve before model validation",
                "Model path empty", method("nativeGetLastErrorVosk", java.lang.Long.TYPE).invoke(engine, handle))
        } finally {
            method("nativeDestroyVosk", java.lang.Long.TYPE).invoke(engine, handle)
        }
    }
}
