package com.sal7one.common_jni.vision

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.OcrLine
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Pinned decoder/projector input, independent of Android storage and model downloaders. */
data class VisionModelFile(val file: File, val bytes: Long, val sha256: String) {
    init {
        require(bytes in 1..ModelIntegrity.MAX_SINGLE_FILE_BYTES)
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Vision model needs a lowercase SHA-256 pin" }
    }
    internal fun verify() {
        check(file.isFile && file.length() == bytes) { "Vision model is missing or has the wrong size: ${file.name}" }
        ModelIntegrity.inspect(file, sha256)
    }
}

/** Local GLM-OCR document reader. Call open/recognize/close off Main.
 *
 * Uses the same pinned llama/mtmd runtime as text translation. Native leases make
 * concurrent cancel/close safe; inference is serialized per session. Cancellation
 * is terminal for this session. A model's image-token budget controls native
 * resolution; maxSide is an input hint, not a detection/rendering promise.
 *
 * Returns a single page-sized text region. It does not invent boxes, table layout
 * or calibrated confidence: -1 means confidence unavailable. No network, Android
 * Context, URI, bitmap, provider key or launcher dependency enters this API.
 */
class VisionOcrSession private constructor(handle: Long) : OcrEngine {
    private val handle = AtomicLong(handle)

    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int): List<OcrLine> {
        require(width in 1..2048 && height in 1..2048 && pixels.size.toLong() == width.toLong() * height)
        require(maxSide in 1..2048)
        val current = handle.get()
        check(current != 0L) { "Vision OCR session is closed" }
        val result = VisionOcrNative.recognize(current, pixels, width, height).toString(Charsets.UTF_8).trim()
        check(result.isNotBlank()) { "Vision OCR returned no readable text" }
        return listOf(OcrLine(0, 0, width, height, result, -1f))
    }

    override fun cancel() { handle.get().takeIf { it != 0L }?.let(VisionOcrNative::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(VisionOcrNative::destroy) }

    companion object {
        fun open(decoder: VisionModelFile, projector: VisionModelFile, threads: Int = 2): VisionOcrSession {
            require(threads in 1..8)
            val protocol = try { VisionOcrNative.protocolVersion() }
                catch (e: UnsatisfiedLinkError) { throw IllegalStateException("Vision OCR runtime is missing or outdated: ${e.message}", e) }
            check(protocol == 1) { "Vision OCR native protocol mismatch: expected 1, got $protocol" }
            check(VisionOcrNative.pointerWidth() == 64) { "GLM-OCR requires a 64-bit process. Choose a smaller OCR reader on this device." }
            decoder.verify(); projector.verify()
            val handle = VisionOcrNative.create(decoder.file.absolutePath.toByteArray(Charsets.UTF_8),
                projector.file.absolutePath.toByteArray(Charsets.UTF_8), threads)
            check(handle != 0L) { "Vision OCR returned an invalid native handle" }
            return VisionOcrSession(handle)
        }
    }
}

internal object VisionOcrNative {
    init { System.loadLibrary("transiber_translation") }
    external fun protocolVersion(): Int
    external fun pointerWidth(): Int
    external fun create(decoder: ByteArray, projector: ByteArray, threads: Int): Long
    external fun recognize(handle: Long, pixels: IntArray, width: Int, height: Int): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
