@file:Suppress("DEPRECATION")

package com.sal7one.common_jni.config

import com.sal7one.common_jni.model.SttMode
import com.sal7one.common_jni.json.JsonInterop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Legacy serialization DTO for the retired process-wide processing settings.
 *
 * These values are not applied to FFmpeg, STT, VAD, TTS, or vision work. New
 * code must configure each STT engine with `SttConfig` and construct each VAD
 * detector with `VadConfig`. The JSON writer remains only for source and data
 * compatibility with callers that persisted the old document shape.
 */
@Deprecated(
    message = "Global processing configuration is unsupported; use per-session SttConfig or VadConfig",
    level = DeprecationLevel.WARNING,
)
data class ProcessingConfig(
    /**
     * Legacy requested STT mode. This value is not applied globally.
     */
    val mode: SttMode = SttMode.BALANCED,
    
    /**
     * Legacy requested audio-gate configuration. This value is not applied globally.
     */
    val audioGate: AudioGateConfig = AudioGateConfig.DEFAULT,
    
    /**
     * Legacy requested native thread count. This value is not applied globally.
     * 0 = auto (hardware_concurrency)
     */
    val threadCount: Int = 0,
    
    /**
     * Legacy profiling request. This value is not applied globally.
     */
    val enableProfiling: Boolean = false,
    
    /**
     * Legacy buffer-pool request. The real native pool has its own fixed policy.
     */
    val bufferPoolSize: Int = 32,
    
    /**
     * Legacy maximum-buffer request. This value is not applied globally.
     */
    val maxBufferSamples: Int = 16000,
    
    /**
     * Legacy NEON request. Runtime architecture selection does not read this value.
     */
    val enableNeon: Boolean = true,
    
    /**
     * Legacy quality request. No runtime quality policy reads this value.
     */
    val qualityFactor: Float = 1.0f,
    
    /**
     * Legacy thermal-adaptation request. No thermal controller reads this value.
     */
    val adaptToThermal: Boolean = true,
    
    /**
     * Legacy CPU-affinity request. No scheduler reads this value.
     */
    val preferredCpuCores: List<Int>? = null
) {
    fun toJson(): String {
        require(threadCount in 0..8) { "threadCount must be in [0,8]" }
        require(bufferPoolSize in 4..128) { "bufferPoolSize must be in [4,128]" }
        require(maxBufferSamples in 1_600..160_000) {
            "maxBufferSamples must be in [1600,160000]"
        }
        require(qualityFactor.isFinite() && qualityFactor in 0.1f..1.0f) {
            "qualityFactor must be finite and in [0.1,1.0]"
        }
        preferredCpuCores?.let { cores ->
            require(cores.size <= 64 && cores.distinct().size == cores.size &&
                cores.all { it in 0..255 }) {
                "preferredCpuCores must contain at most 64 unique IDs in [0,255]"
            }
        }

        val json = JSONObject()
            .put("mode", mode.nativeValue)
            .put("audioGate", audioGate.toJsonObject())
            .put("threadCount", threadCount)
            .put("enableProfiling", enableProfiling)
            .put("bufferPoolSize", bufferPoolSize)
            .put("maxBufferSamples", maxBufferSamples)
            .put("enableNeon", enableNeon)
            .put("qualityFactor", qualityFactor)
            .put("adaptToThermal", adaptToThermal)
        preferredCpuCores?.let { cores ->
            val array = JSONArray()
            cores.forEach(array::put)
            json.put("preferredCpuCores", array)
        }
        return JsonInterop.asciiString(json)
    }
    
    class Builder {
        private var mode: SttMode = SttMode.BALANCED
        private var audioGate: AudioGateConfig = AudioGateConfig.DEFAULT
        private var threadCount: Int = 0
        private var enableProfiling: Boolean = false
        private var bufferPoolSize: Int = 32
        private var maxBufferSamples: Int = 16000
        private var enableNeon: Boolean = true
        private var qualityFactor: Float = 1.0f
        private var adaptToThermal: Boolean = true
        private var preferredCpuCores: List<Int>? = null
        
        fun mode(m: SttMode) = apply { mode = m }
        fun audioGate(config: AudioGateConfig) = apply { audioGate = config }
        fun threadCount(count: Int) = apply { threadCount = count.coerceIn(0, 8) }
        fun enableProfiling(enable: Boolean) = apply { enableProfiling = enable }
        fun bufferPoolSize(size: Int) = apply { bufferPoolSize = size.coerceIn(4, 128) }
        fun maxBufferSamples(samples: Int) = apply { maxBufferSamples = samples.coerceIn(1600, 160000) }
        fun enableNeon(enable: Boolean) = apply { enableNeon = enable }
        fun qualityFactor(factor: Float) = apply { qualityFactor = factor.coerceIn(0.1f, 1.0f) }
        fun adaptToThermal(adapt: Boolean) = apply { adaptToThermal = adapt }
        fun preferredCpuCores(cores: List<Int>?) = apply { preferredCpuCores = cores }
        
        /** Legacy serialized preset; not applied to a runtime pipeline. */
        fun fast() = apply {
            mode = SttMode.FAST
            audioGate = AudioGateConfig.AGGRESSIVE
            threadCount = 2
            qualityFactor = 0.7f
        }
        
        /** Legacy serialized preset; not applied to a runtime pipeline. */
        fun balanced() = apply {
            mode = SttMode.BALANCED
            audioGate = AudioGateConfig.DEFAULT
            threadCount = 4
            qualityFactor = 0.85f
        }
        
        /** Legacy serialized preset; not applied to a runtime pipeline. */
        fun accurate() = apply {
            mode = SttMode.ACCURATE
            audioGate = AudioGateConfig.SENSITIVE
            threadCount = 6
            qualityFactor = 1.0f
        }
        
        /** Legacy serialized preset; not applied to a runtime pipeline. */
        fun debug() = apply {
            enableProfiling = true
            enableNeon = false
            adaptToThermal = false
        }
        
        fun build() = ProcessingConfig(
            mode, audioGate, threadCount, enableProfiling, bufferPoolSize,
            maxBufferSamples, enableNeon, qualityFactor, adaptToThermal, preferredCpuCores
        )
    }
    
    companion object {
        val DEFAULT = ProcessingConfig()
        val FAST = Builder().fast().build()
        val BALANCED = Builder().balanced().build()
        val ACCURATE = Builder().accurate().build()
        val DEBUG = Builder().debug().build()
    }
}
