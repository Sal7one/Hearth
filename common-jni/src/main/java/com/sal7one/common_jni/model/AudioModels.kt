package com.sal7one.common_jni.model

import com.sal7one.common_jni.json.JsonInterop
import org.json.JSONObject

/**
 * Unified audio chunk for STT processing.
 * All audio pipelines (mic, file, ffmpeg) produce this format.
 */
data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val durationMs: Long
) {
    val sampleCount: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        return samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs &&
                durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }

    companion object {
        fun empty(sampleRate: Int = SttConfig.SAMPLE_RATE_16K) = AudioChunk(
            samples = ShortArray(0),
            sampleRate = sampleRate,
            timestampMs = 0L,
            durationMs = 0L
        )

        fun create(samples: ShortArray, sampleRate: Int, timestampMs: Long): AudioChunk {
            val durationMs = (samples.size * 1000L) / sampleRate
            return AudioChunk(samples, sampleRate, timestampMs, durationMs)
        }
    }
}

/**
 * Float audio chunk for engines that prefer float input.
 */
data class AudioChunkFloat(
    val samples: FloatArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val durationMs: Long
) {
    val sampleCount: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunkFloat
        return samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        return result
    }

    companion object {
        fun fromInt16(chunk: AudioChunk): AudioChunkFloat {
            val floatSamples = FloatArray(chunk.sampleCount) { i ->
                chunk.samples[i] / 32768.0f
            }
            return AudioChunkFloat(
                samples = floatSamples,
                sampleRate = chunk.sampleRate,
                timestampMs = chunk.timestampMs,
                durationMs = chunk.durationMs
            )
        }
    }
}

/**
 * A single transcription segment with timing.
 */
data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1.0f,
    val isFinal: Boolean = true,
    val speakerId: Int? = null
) {
    val durationMs: Long get() = endMs - startMs
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Complete transcription result.
 */
data class TranscriptResult(
    val segments: List<TranscriptSegment>,
    val fullText: String,
    val language: String?,
    val processingTimeMs: Long,
    val engineUsed: SttEngineType? = null
) {
    val isEmpty: Boolean get() = segments.isEmpty() && fullText.isBlank()
    val wordCount: Int get() = fullText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

    companion object {
        val EMPTY = TranscriptResult(
            segments = emptyList(),
            fullText = "",
            language = null,
            processingTimeMs = 0L
        )

        fun fromJson(json: String, engineUsed: SttEngineType? = null): TranscriptResult {
            return try {
                val root = JsonInterop.objectOrNull(json) ?: return EMPTY
                val text = JsonInterop.stringOrNull(root, "text") ?: ""
                val language = JsonInterop.stringOrNull(root, "language")
                val processingTimeMs = JsonInterop.longOrDefault(root, "processingTimeMs", 0L)
                val segments = parseSegments(root)

                TranscriptResult(
                    segments = segments,
                    fullText = text.trim(),
                    language = language,
                    processingTimeMs = processingTimeMs,
                    engineUsed = engineUsed
                )
            } catch (e: Exception) {
                EMPTY
            }
        }

        private fun parseSegments(root: JSONObject): List<TranscriptSegment> {
            val segments = mutableListOf<TranscriptSegment>()
            val values = JsonInterop.arrayOrNull(root, "segments") ?: return segments
            for (index in 0 until values.length()) {
                val segment = JsonInterop.objectOrNull(values, index) ?: continue
                val text = JsonInterop.stringOrNull(segment, "text") ?: ""
                val startMs = JsonInterop.longOrDefault(segment, "startMs", 0L)
                val endMs = JsonInterop.longOrDefault(segment, "endMs", 0L)
                val confidence = JsonInterop.floatOrDefault(segment, "confidence", 0.9f)

                if (text.isNotBlank()) {
                    segments.add(
                        TranscriptSegment(
                            text = text.trim(),
                            startMs = startMs,
                            endMs = endMs,
                            confidence = confidence,
                            isFinal = true
                        )
                    )
                }
            }
            return segments
        }
    }
}

/**
 * Partial transcript for streaming mode.
 */
data class PartialTranscript(
    val text: String,
    val timestampMs: Long,
    val isStable: Boolean = false
) {
    val isEmpty: Boolean get() = text.isBlank()

    companion object {
        val EMPTY = PartialTranscript("", 0L, false)
    }
}
