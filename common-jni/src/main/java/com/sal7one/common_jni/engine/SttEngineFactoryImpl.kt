package com.sal7one.common_jni.engine

import com.sal7one.common_jni.engine.onnx.OnnxEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.model.AudioDecoderType
import com.sal7one.common_jni.model.SttCapabilities
import com.sal7one.common_jni.model.SttEngineType

/**
 * Factory for creating STT engine instances.
 * Queries native layer for available engines.
 */
class SttEngineFactoryImpl : SttEngineFactory {

    companion object {
        @Volatile
        private var instance: SttEngineFactoryImpl? = null

        fun getInstance(): SttEngineFactoryImpl {
            return instance ?: synchronized(this) {
                instance ?: SttEngineFactoryImpl().also { instance = it }
            }
        }

        init {
            try {
                System.loadLibrary("common_jni")
            } catch (e: UnsatisfiedLinkError) {
                // Library loading errors handled in individual engines
            }
        }

        @JvmStatic
        private external fun nativeGetCapabilityMask(): Int
    }

    private val capabilityMask: Int by lazy {
        try {
            nativeGetCapabilityMask()
        } catch (e: Exception) {
            0
        }
    }

    override fun create(type: SttEngineType): Result<SttEngine> {
        if (!isAvailable(type)) {
            return Result.failure(SttError.EngineNotAvailable(type))
        }

        return try {
            val engine: SttEngine = when (type) {
                SttEngineType.WHISPER -> WhisperEngine()
                SttEngineType.VOSK -> VoskEngine()
                SttEngineType.ONNX -> OnnxEngine()
            }
            Result.success(engine)
        } catch (e: Exception) {
            Result.failure(SttError.InitializationFailed(e.message ?: "Failed to create engine"))
        }
    }

    override fun isAvailable(type: SttEngineType): Boolean {
        return (capabilityMask and type.bitFlag) != 0
    }

    override fun availableEngines(): List<SttEngineType> {
        return SttEngineType.entries.filter { isAvailable(it) }
    }

    override fun getCapabilities(): SttCapabilities {
        return SttCapabilities.fromMask(capabilityMask)
    }

    /**
     * Get the best available engine (preference: Whisper > ONNX > Vosk).
     */
    fun getBestEngine(): SttEngineType? {
        return when {
            isAvailable(SttEngineType.WHISPER) -> SttEngineType.WHISPER
            isAvailable(SttEngineType.ONNX) -> SttEngineType.ONNX
            isAvailable(SttEngineType.VOSK) -> SttEngineType.VOSK
            else -> null
        }
    }

    /**
     * Check if FFmpeg audio decoding is available.
     */
    fun isFfmpegAvailable(): Boolean {
        return (capabilityMask and AudioDecoderType.FFMPEG.bitFlag) != 0
    }
}
