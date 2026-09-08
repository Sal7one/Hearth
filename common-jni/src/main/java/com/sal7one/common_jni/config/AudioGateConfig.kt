@file:Suppress("DEPRECATION")

package com.sal7one.common_jni.config

import com.sal7one.common_jni.json.JsonInterop
import org.json.JSONObject

/**
 * Legacy serialization DTO for the retired process-wide audio-gate settings.
 *
 * This object does not configure live STT or VAD processing. New code should
 * use `SttConfig` for an STT session or `VadConfig` when constructing a
 * `VadDetector`. The JSON writer remains only for source and data compatibility
 * with callers that persisted the old document shape.
 */
@Deprecated(
    message = "Global audio-gate configuration is unsupported; use per-session SttConfig or VadConfig",
    level = DeprecationLevel.WARNING,
)
data class AudioGateConfig(
    /**
     * Legacy requested RMS threshold. This value is not applied globally.
     */
    val rmsThresholdDb: Float = -40f,
    
    /**
     * Legacy requested maximum zero-crossing rate. This value is not applied globally.
     */
    val zeroCrossingMax: Float = 0.3f,
    
    /**
     * Legacy requested minimum active duration. This value is not applied globally.
     */
    val minActiveDurationMs: Int = 300,
    
    /**
     * Legacy requested minimum silence duration. This value is not applied globally.
     */
    val minSilenceDurationMs: Int = 500,
    
    /**
     * Legacy requested smoothing factor. This value is not applied globally.
     */
    val smoothingAlpha: Float = 0.1f,
    
    /**
     * Legacy requested sample rate. This value is not applied globally.
     */
    val sampleRate: Int = 16000,
    
    /**
     * Legacy requested enabled state. This value is not applied globally.
     */
    val enabled: Boolean = true
) {
    /** Serialize the retired document shape for persisted-data compatibility. */
    fun toJson(): String = JsonInterop.asciiString(toJsonObject())

    internal fun toJsonObject(): JSONObject {
        require(rmsThresholdDb.isFinite() && rmsThresholdDb in -60f..-10f) {
            "rmsThresholdDb must be finite and in [-60,-10]"
        }
        require(zeroCrossingMax.isFinite() && zeroCrossingMax in 0.05f..0.8f) {
            "zeroCrossingMax must be finite and in [0.05,0.8]"
        }
        require(smoothingAlpha.isFinite() && smoothingAlpha in 0.01f..0.5f) {
            "smoothingAlpha must be finite and in [0.01,0.5]"
        }
        require(sampleRate in 8_000..192_000) { "sampleRate must be in [8000,192000]" }
        require(minActiveDurationMs in 1..2_000) { "minActiveDurationMs must be in [1,2000]" }
        require(minSilenceDurationMs in 1..5_000) { "minSilenceDurationMs must be in [1,5000]" }

        val activeSamples = minActiveDurationMs.toLong() * sampleRate / 1_000L
        val silenceSamples = minSilenceDurationMs.toLong() * sampleRate / 1_000L
        require(activeSamples in 1..Int.MAX_VALUE.toLong()) { "minActiveSamples overflow" }
        require(silenceSamples in 1..Int.MAX_VALUE.toLong()) { "minSilenceSamples overflow" }

        return JSONObject()
            .put("rmsThresholdDb", rmsThresholdDb)
            .put("zeroCrossingMax", zeroCrossingMax)
            .put("minActiveSamples", activeSamples.toInt())
            .put("minSilenceSamples", silenceSamples.toInt())
            .put("smoothingAlpha", smoothingAlpha)
            .put("sampleRate", sampleRate)
            .put("enabled", enabled)
    }
    
    class Builder {
        private var rmsThresholdDb: Float = -40f
        private var zeroCrossingMax: Float = 0.3f
        private var minActiveDurationMs: Int = 300
        private var minSilenceDurationMs: Int = 500
        private var smoothingAlpha: Float = 0.1f
        private var sampleRate: Int = 16000
        private var enabled: Boolean = true
        
        fun rmsThresholdDb(db: Float) = apply { rmsThresholdDb = db.coerceIn(-60f, -10f) }
        fun zeroCrossingMax(zcr: Float) = apply { zeroCrossingMax = zcr.coerceIn(0.05f, 0.8f) }
        fun minActiveDurationMs(ms: Int) = apply { minActiveDurationMs = ms.coerceIn(50, 2000) }
        fun minSilenceDurationMs(ms: Int) = apply { minSilenceDurationMs = ms.coerceIn(100, 5000) }
        fun smoothingAlpha(alpha: Float) = apply { smoothingAlpha = alpha.coerceIn(0.01f, 0.5f) }
        fun sampleRate(rate: Int) = apply { sampleRate = rate }
        fun enabled(enable: Boolean) = apply { enabled = enable }
        
        fun build() = AudioGateConfig(
            rmsThresholdDb, zeroCrossingMax, minActiveDurationMs,
            minSilenceDurationMs, smoothingAlpha, sampleRate, enabled
        )
    }
    
    companion object {
        /** Legacy default document preset; not applied to a live detector. */
        val DEFAULT = AudioGateConfig()
        
        /** Legacy sensitive document preset; not applied to a live detector. */
        val SENSITIVE = AudioGateConfig(
            rmsThresholdDb = -50f,
            zeroCrossingMax = 0.4f,
            minActiveDurationMs = 200,
            minSilenceDurationMs = 300
        )
        
        /** Legacy aggressive document preset; not applied to a live detector. */
        val AGGRESSIVE = AudioGateConfig(
            rmsThresholdDb = -30f,
            zeroCrossingMax = 0.25f,
            minActiveDurationMs = 400,
            minSilenceDurationMs = 800
        )
        
        /** Legacy disabled document preset; not applied to a live detector. */
        val DISABLED = AudioGateConfig(enabled = false)
    }
}
