package com.sal7one.common_jni.translation

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.SpeechTextTranslator
import com.sal7one.common_jni.speech.TranslationDirection
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object LocalTranslationNative {
    init { System.loadLibrary("transiber_translation") }
    external fun promptProtocolVersion(): Int
    external fun create(path: ByteArray, rawPrompt: Boolean): Long
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
/** Kept in a separate DSO so choosing CPU never initializes a phone's Vulkan driver. */
internal object LocalTranslationVulkanNative {
    init { System.loadLibrary("transiber_translation_vulkan") }
    external fun promptProtocolVersion(): Int
    external fun create(path: ByteArray, rawPrompt: Boolean): Long
    external fun executionInfo(handle: Long): ByteArray
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
/** Loaded only by an explicit Adreno choice; CPU and TVs never touch its driver. */
internal object LocalTranslationOpenClNative {
    init { System.loadLibrary("transiber_translation_opencl") }
    external fun promptProtocolVersion(): Int
    external fun configureCache(directory: ByteArray)
    external fun create(path: ByteArray, rawPrompt: Boolean): Long
    external fun executionInfo(handle: Long): ByteArray
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
internal interface TranslationRuntime {
    fun configure(cacheDirectory: File?) = Unit
    fun protocolVersion(): Int
    fun create(path: ByteArray, rawPrompt: Boolean): Long
    fun executionInfo(handle: Long): String
    fun translate(handle: Long, prompt: ByteArray): ByteArray
    fun cancel(handle: Long)
    fun destroy(handle: Long)
}
private object CpuTranslationRuntime : TranslationRuntime {
    override fun protocolVersion() = LocalTranslationNative.promptProtocolVersion()
    override fun create(path: ByteArray, rawPrompt: Boolean) = LocalTranslationNative.create(path, rawPrompt)
    override fun executionInfo(handle: Long) = "CPU · llama.cpp"
    override fun translate(handle: Long, prompt: ByteArray) = LocalTranslationNative.translate(handle, prompt)
    override fun cancel(handle: Long) = LocalTranslationNative.cancel(handle)
    override fun destroy(handle: Long) = LocalTranslationNative.destroy(handle)
}
private object VulkanTranslationRuntime : TranslationRuntime {
    override fun protocolVersion() = LocalTranslationVulkanNative.promptProtocolVersion()
    override fun create(path: ByteArray, rawPrompt: Boolean) = LocalTranslationVulkanNative.create(path, rawPrompt)
    override fun executionInfo(handle: Long) = LocalTranslationVulkanNative.executionInfo(handle).toString(Charsets.UTF_8).trim()
        .also { check(it.isNotBlank()) { "Vulkan translation returned no execution information" } }
    override fun translate(handle: Long, prompt: ByteArray) = LocalTranslationVulkanNative.translate(handle, prompt)
    override fun cancel(handle: Long) = LocalTranslationVulkanNative.cancel(handle)
    override fun destroy(handle: Long) = LocalTranslationVulkanNative.destroy(handle)
}
private object OpenClTranslationRuntime : TranslationRuntime {
    override fun configure(cacheDirectory: File?) {
        val directory = cacheDirectory?.absoluteFile?.also {
            check((it.isDirectory || it.mkdirs()) && it.canWrite()) { "OpenCL kernel cache directory is unavailable: $it" }
        }?.path.orEmpty()
        LocalTranslationOpenClNative.configureCache(directory.toByteArray(Charsets.UTF_8))
    }
    override fun protocolVersion() = LocalTranslationOpenClNative.promptProtocolVersion()
    override fun create(path: ByteArray, rawPrompt: Boolean) = LocalTranslationOpenClNative.create(path, rawPrompt)
    override fun executionInfo(handle: Long) = LocalTranslationOpenClNative.executionInfo(handle).toString(Charsets.UTF_8).trim()
        .also { check(it.isNotBlank()) { "OpenCL translation returned no execution information" } }
    override fun translate(handle: Long, prompt: ByteArray) = LocalTranslationOpenClNative.translate(handle, prompt)
    override fun cancel(handle: Long) = LocalTranslationOpenClNative.cancel(handle)
    override fun destroy(handle: Long) = LocalTranslationOpenClNative.destroy(handle)
}
interface CancellableTextTranslator : SpeechTextTranslator, AutoCloseable {
    fun cancel()
    val executionInfo: String? get() = null
}
/** One model/context per bridge. Calls are serialized natively; close cancels before retiring the handle. */
class LocalTranslationSession private constructor(
    private val spec: TranslationModelSpec,
    handle: Long,
    private val runtime: TranslationRuntime,
    override val executionInfo: String,
) : CancellableTextTranslator {
    private val handle = AtomicLong(handle)
    override val id get() = spec.id
    override val directions get() = spec.directions
    override suspend fun translate(text: String, direction: TranslationDirection): String = withContext(Dispatchers.IO) {
        val current = handle.get(); check(current != 0L) { "Local translation session is closed" }
        val prompt = spec.prompt(text, direction.source, direction.target)
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        runtime.translate(current, bytes).toString(Charsets.UTF_8).trim()
            .also { check(it.isNotBlank()) { "Local translation returned empty text" } }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(runtime::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(runtime::destroy) }
    companion object {
        /** Blocking ownership-safe load; caller must keep this off Main and close even after cancellation. */
        fun open(file: File, spec: TranslationModelSpec, acceleration: TranslationAcceleration = TranslationAcceleration.CPU,
            cacheDirectory: File? = null): LocalTranslationSession =
            openWithRuntime(file, spec, acceleration, cacheDirectory) { selected ->
                when (selected) {
                    TranslationAcceleration.CPU -> CpuTranslationRuntime
                    TranslationAcceleration.VULKAN -> VulkanTranslationRuntime
                    TranslationAcceleration.OPENCL -> OpenClTranslationRuntime
                }
            }

        internal fun openWithRuntime(file: File, spec: TranslationModelSpec, acceleration: TranslationAcceleration,
            cacheDirectory: File? = null,
            resolve: (TranslationAcceleration) -> TranslationRuntime): LocalTranslationSession {
            val runtime = resolve(acceleration)
            val gpuName = if (acceleration == TranslationAcceleration.OPENCL) "OpenCL" else "Vulkan"
            val protocol = try {
                runtime.configure(cacheDirectory)
                runtime.protocolVersion()
            } catch (e: LinkageError) {
                if (acceleration != TranslationAcceleration.CPU) throw IllegalStateException(
                    "$gpuName translation runtime is unavailable: ${e.message}. Select CPU in Models → Translation.", e)
                throw IllegalStateException("Local translation native runtime is outdated: ${e.message}", e)
            }
            check(protocol == 2) { "Local translation native prompt protocol mismatch: expected 2, got $protocol" }
            check(file.length() == spec.bytes) { "Local translation model is missing or has the wrong size" }
            ModelIntegrity.inspect(file, spec.sha256)
            val path = file.absolutePath.toByteArray(Charsets.UTF_8)
            val handle = try {
                runtime.create(path, spec.family == "milmmt-46")
            } catch (e: Exception) {
                if (acceleration != TranslationAcceleration.CPU) throw IllegalStateException(
                    "$gpuName translation could not start: ${e.message}. Select CPU in Models → Translation.", e)
                throw e
            } catch (e: LinkageError) {
                if (acceleration != TranslationAcceleration.CPU) throw IllegalStateException(
                    "$gpuName translation runtime is incompatible: ${e.message}. Select CPU in Models → Translation.", e)
                throw e
            }
            check(handle != 0L) { "Local translation returned an invalid native handle" }
            // If metadata retrieval fails, the just-created context still belongs to this call.
            try {
                val info = runtime.executionInfo(handle)
                check(info.isNotBlank()) { "Local translation returned no execution information" }
                return LocalTranslationSession(spec, handle, runtime, info)
            } catch (e: Throwable) {
                try { runtime.destroy(handle) } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }
}
