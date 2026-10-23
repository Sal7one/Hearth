package com.sal7one.hearth.integration

import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.vision.OcrRect
import kotlinx.coroutines.flow.StateFlow
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil

/** The desktop-owned reader supplies OCR and the existing translation pipeline; HTTP owns no engines. */
interface IntegrationReadingService {
    fun capabilities(): IntegrationReadingCapabilities
    /** Returns immediately. The session owns this bounded, decoded image until its actual terminal state. */
    fun start(request: IntegrationReadingRequest): IntegrationReadingSession
}

data class IntegrationReadingCapabilities(
    val available: Boolean,
    val sourceLanguages: Set<String>,
    val unavailableReason: String? = null,
)

data class IntegrationReadingRequest(
    val image: BufferedImage,
    val config: PipelineConfig,
    val translate: Boolean,
)

interface IntegrationReadingSession {
    val id: String
    val state: StateFlow<IntegrationReadingSnapshot>
    /** Cancels and joins native work before returning; cancelling a completed session is harmless. */
    suspend fun cancel()
}

enum class IntegrationReadingStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

data class IntegrationReadingRegion(
    val id: String,
    val bounds: OcrRect,
    val text: String? = null,
    val translation: String? = null,
)

data class IntegrationReadingSnapshot(
    val id: String,
    val status: IntegrationReadingStatus,
    val width: Int,
    val height: Int,
    val phase: String,
    val regions: List<IntegrationReadingRegion> = emptyList(),
    val error: String? = null,
) {
    val terminal: Boolean get() = status != IntegrationReadingStatus.RUNNING
}

/** Header validation precedes pixel allocation; no disk cache, local paths or network image resolvers. */
internal object IntegrationImage {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_SIDE = 2048
    const val MAX_SOURCE_PIXELS = 100_000_000L
    val contentTypes = setOf("image/png", "image/jpeg")
    data class Decoded(val image: BufferedImage, val sourceWidth: Int, val sourceHeight: Int)

    fun decode(bytes: ByteArray, contentType: String): Decoded {
        require(contentType in contentTypes) { "Upload image/png or image/jpeg" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Image must contain 1–16 MiB of data" }
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Image is not a readable PNG or JPEG" }
            val reader = readers.next()
            try {
                val format = reader.formatName.lowercase()
                require((contentType == "image/png" && format == "png") ||
                    (contentType == "image/jpeg" && format in setOf("jpeg", "jpg"))) {
                    "Image content does not match its Content-Type"
                }
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 8..32_768 && height in 8..32_768 && width.toLong() * height <= MAX_SOURCE_PIXELS) {
                    "Image must be at least 8 × 8 and at most 100 megapixels, with no side above 32768 pixels"
                }
                val step = ceil(maxOf(width, height).toDouble() / MAX_SIDE).toInt().coerceAtLeast(1)
                val params = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                val image = reader.read(0, params) ?: error("Image decoder returned no pixels")
                check(image.width in 1..MAX_SIDE && image.height in 1..MAX_SIDE) { "Decoded image exceeds the reader limit" }
                return Decoded(image, width, height)
            } finally { reader.dispose() }
        }
    }
}
