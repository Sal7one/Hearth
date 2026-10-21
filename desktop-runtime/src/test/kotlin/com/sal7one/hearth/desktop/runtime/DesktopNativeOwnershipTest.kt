package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopBackendSpeechNative
import com.sal7one.common_jni.desktop.DesktopQwenNative
import com.sal7one.common_jni.translation.Ct2Native
import com.sal7one.common_jni.desktop.DesktopWhisperNative
import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Handle-ownership guarantees for the desktop JNI layer, which the UI depends on
 * when a session is cancelled, restarted or closed out of order.
 *
 * These run without a model file: rejecting a bad handle must never require one.
 */
class DesktopNativeOwnershipTest {
    @Test
    fun operationsOnAnUnknownHandleFailClearly() {
        val text = assertFailsWith<IllegalStateException> {
            DesktopWhisperNative.transcribe(0L, floatArrayOf())
        }
        assertContains(text.message.orEmpty(), "closed", ignoreCase = true)

        val info = assertFailsWith<IllegalStateException> { DesktopWhisperNative.executionInfo(0L) }
        assertContains(info.message.orEmpty(), "closed", ignoreCase = true)

        val language = assertFailsWith<IllegalStateException> { DesktopWhisperNative.language(0L) }
        assertContains(language.message.orEmpty(), "closed", ignoreCase = true)

        // A handle that was never issued is rejected the same way as a retired one.
        assertFailsWith<IllegalStateException> { DesktopWhisperNative.transcribe(9_999_999L, floatArrayOf()) }
    }

    @Test
    fun retiringAnUnknownHandleIsANoOp() {
        // Cancelling and destroying must be idempotent: a UI teardown can race a close.
        DesktopWhisperNative.cancel(0L)
        DesktopWhisperNative.destroy(0L)
        DesktopWhisperNative.cancel(9_999_999L)
        DesktopWhisperNative.destroy(9_999_999L)
        DesktopWhisperNative.destroy(9_999_999L)
    }

    @Test
    fun creationSurfacesRealErrorsInsteadOfReturningAHandle() {
        val bogus = Path.of("/nonexistent/hearth-smoke/missing.bin")
        val loadFailure = assertFailsWith<IllegalStateException> {
            DesktopWhisperNative.create(bogus.toString().toByteArray(), "en".toByteArray(), 2, false)
        }
        assertTrue(loadFailure.message.orEmpty().isNotBlank(), "A failed load must carry a reason")

        // Validation happens before the model is touched, so this cannot depend on the file.
        val languageFailure = assertFailsWith<IllegalStateException> {
            DesktopWhisperNative.create(bogus.toString().toByteArray(), "zz".toByteArray(), 2, false)
        }
        assertContains(languageFailure.message.orEmpty(), "language", ignoreCase = true)

        val threadFailure = assertFailsWith<IllegalStateException> {
            DesktopWhisperNative.create(bogus.toString().toByteArray(), "en".toByteArray(), 0, false)
        }
        assertContains(threadFailure.message.orEmpty(), "threads", ignoreCase = true)
    }

    @Test
    fun destroyingAModelTwiceIsSafeAndUseAfterDestroyIsRejected() {
        assumeTrue(
            "Set HEARTH_WHISPER_SMOKE=1 and provide HEARTH_SMOKE_WHISPER",
            System.getenv("HEARTH_WHISPER_SMOKE") == "1",
        )
        val model = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WHISPER"))).toRealPath()
        val handle = DesktopWhisperNative.create(model.toString().toByteArray(), "en".toByteArray(), 2, false)
        assertTrue(handle > 0, "A successful create must return a live handle")

        DesktopWhisperNative.destroy(handle)
        // Second destroy must not crash or free twice.
        DesktopWhisperNative.destroy(handle)

        val afterDestroy = assertFailsWith<IllegalStateException> {
            DesktopWhisperNative.transcribe(handle, floatArrayOf(0f, 0f, 0f))
        }
        assertContains(afterDestroy.message.orEmpty(), "closed", ignoreCase = true)

        // Handles are monotonic and never reused: a fresh model must not inherit the old one.
        val next = DesktopWhisperNative.create(model.toString().toByteArray(), "en".toByteArray(), 2, false)
        try {
            assertTrue(next != handle, "Handle values must never be reused")
        } finally {
            DesktopWhisperNative.destroy(next)
        }
    }

    @Test
    fun qwenRejectsUnknownHandlesAndRetiresIdempotently() {
        assumeTrue("Qwen3-ASR bundle is staged for this host", runCatching { DesktopQwenNative.protocolVersion() == 1 }.getOrDefault(false))
        val text = assertFailsWith<IllegalStateException> { DesktopQwenNative.transcribe(0L, floatArrayOf()) }
        assertContains(text.message.orEmpty(), "closed", ignoreCase = true)
        assertFailsWith<IllegalStateException> { DesktopQwenNative.language(0L) }
        assertFailsWith<IllegalStateException> { DesktopQwenNative.executionInfo(0L) }
        DesktopQwenNative.cancel(0L)
        DesktopQwenNative.destroy(0L)
        DesktopQwenNative.cancel(9_999_999L)
        DesktopQwenNative.destroy(9_999_999L)
    }

    @Test
    fun qwenCreationSurfacesRealErrorsInsteadOfReturningAHandle() {
        assumeTrue("Qwen3-ASR bundle is staged for this host", runCatching { DesktopQwenNative.protocolVersion() == 1 }.getOrDefault(false))
        val bogus = Path.of("/nonexistent/hearth-qwen/missing")
        val loadFailure = assertFailsWith<IllegalStateException> {
            DesktopQwenNative.create(bogus.toString().toByteArray(), "en".toByteArray(), 2)
        }
        assertContains(loadFailure.message.orEmpty(), "Qwen3-ASR")
        val languageFailure = assertFailsWith<IllegalStateException> {
            DesktopQwenNative.create(bogus.toString().toByteArray(), "zz".toByteArray(), 2)
        }
        assertContains(languageFailure.message.orEmpty(), "language", ignoreCase = true)
        val threadFailure = assertFailsWith<IllegalStateException> {
            DesktopQwenNative.create(bogus.toString().toByteArray(), "en".toByteArray(), 0)
        }
        assertContains(threadFailure.message.orEmpty(), "threads", ignoreCase = true)
    }

    @Test
    fun ct2RejectsUnknownHandlesAndCreationErrors() {
        assumeTrue("CTranslate2 bundle is staged for this host", runCatching { Ct2Native.protocolVersion() == 2 }.getOrDefault(false))
        assertFailsWith<RuntimeException> { Ct2Native.translate(0L, "hello".toByteArray(), "ar".toByteArray()) }
        val failure = assertFailsWith<RuntimeException> { Ct2Native.create(Path.of("/nonexistent/hearth-ct2").toString().toByteArray(), 0) }
        assertTrue(failure.message.orEmpty().isNotBlank())
        Ct2Native.cancel(0L)
        Ct2Native.destroy(0L)
    }

    @Test
    fun backendBridgeRejectsUnknownHandlesAndMissingLibraries() {
        assumeTrue("Speech backend bridge is staged for this host", runCatching { DesktopBackendSpeechNative.protocolVersion() == 1 }.getOrDefault(false))
        assertFailsWith<IllegalStateException> { DesktopBackendSpeechNative.transcribe(0L, floatArrayOf()) }
        assertFailsWith<IllegalStateException> { DesktopBackendSpeechNative.executionInfo(0L) }
        val failure = assertFailsWith<IllegalStateException> {
            DesktopBackendSpeechNative.create(
                "/nonexistent/libhearth_transcribepsy.dylib".toByteArray(), "transcribepsy".toByteArray(),
                "/nonexistent/model.gguf".toByteArray(), "en".toByteArray(), 2, 3, 15000, 600, -45f,
            )
        }
        assertContains(failure.message.orEmpty(), "Could not load speech backend")
        DesktopBackendSpeechNative.cancel(0L)
        DesktopBackendSpeechNative.destroy(0L)
    }
}
