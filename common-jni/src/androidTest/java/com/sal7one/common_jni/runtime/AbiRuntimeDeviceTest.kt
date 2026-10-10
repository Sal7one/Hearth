package com.sal7one.common_jni.runtime

import android.os.Process
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
        arguments.getString("expectedBits")?.let { assertEquals(it == "64", Process.is64Bit()) }
        CommonJni.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("Whisper missing", CommonJni.hasWhisper())
        assertTrue("Vosk missing", CommonJni.hasVosk())
        checkVoskCapiLoads()
        assertTrue("ONNX missing", CommonJni.hasOnnx())
        assertTrue("Marian runtime missing", MarianNative.nativeRuntimeAvailable())
        assertEquals("Translation JNI prompt protocol", 2, LocalTranslationNative.promptProtocolVersion())
        for (backend in SpeechBackend.entries) {
            val availability = SpeechRuntime().availability(backend)
            assertTrue("${backend.id}: ${availability.error}", availability.available)
            assertFalse("${backend.id}: revision missing", availability.revision.isNullOrBlank())
        }
        Log.i("HearthAbiSmoke", "native libraries and all speech ABI exports PASS; processBits=${if (Process.is64Bit()) 64 else 32}")
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
