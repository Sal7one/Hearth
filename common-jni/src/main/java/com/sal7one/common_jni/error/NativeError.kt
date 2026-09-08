package com.sal7one.common_jni.error

/**
 * Native error codes matching C++ ErrorCode enum.
 * Keep in sync with error_codes.h
 */
sealed class NativeError(val code: Int, val message: String) {
    // General errors (1-99)
    data class Unknown(override val msg: String = "Unknown error") : NativeError(1, msg)
    data class InvalidArgument(override val msg: String) : NativeError(2, msg)
    data class InvalidState(override val msg: String) : NativeError(3, msg)
    data class NotInitialized(override val msg: String = "Not initialized") : NativeError(4, msg)
    data class AlreadyInitialized(override val msg: String = "Already initialized") : NativeError(5, msg)
    data class OutOfMemory(override val msg: String = "Out of memory") : NativeError(6, msg)
    
    // Handle errors (100-199)
    data class InvalidHandle(override val msg: String = "Invalid handle") : NativeError(100, msg)
    data class HandleNotFound(override val msg: String = "Handle not found") : NativeError(101, msg)
    data class HandleAlreadyReleased(override val msg: String = "Handle already released") : NativeError(102, msg)
    
    // Engine errors (200-299)
    data class EngineNotAvailable(override val msg: String) : NativeError(200, msg)
    data class EngineLoadFailed(override val msg: String) : NativeError(201, msg)
    data class EngineInferenceFailed(override val msg: String) : NativeError(202, msg)
    data class EngineCancelled(override val msg: String = "Operation cancelled") : NativeError(203, msg)
    
    // Model errors (300-399)
    data class ModelNotFound(override val msg: String) : NativeError(300, msg)
    data class ModelLoadFailed(override val msg: String) : NativeError(301, msg)
    data class ModelInvalidFormat(override val msg: String) : NativeError(302, msg)
    data class ModelVersionMismatch(override val msg: String) : NativeError(303, msg)
    
    // Audio errors (400-499)
    data class AudioDecodeFailed(override val msg: String) : NativeError(400, msg)
    data class AudioEncodeFailed(override val msg: String) : NativeError(401, msg)
    data class AudioInvalidFormat(override val msg: String) : NativeError(402, msg)
    data class AudioFileNotFound(override val msg: String) : NativeError(403, msg)
    
    // FFmpeg errors (500-599)
    data class FFmpegNotAvailable(override val msg: String = "FFmpeg not available") : NativeError(500, msg)
    data class FFmpegOpenFailed(override val msg: String) : NativeError(501, msg)
    data class FFmpegDecodeFailed(override val msg: String) : NativeError(502, msg)
    data class FFmpegEncodeFailed(override val msg: String) : NativeError(503, msg)
    data class FFmpegNoAudioStream(override val msg: String) : NativeError(504, msg)
    data class FFmpegEncoderNotFound(override val msg: String) : NativeError(505, msg)
    
    // Threading errors (600-699)
    data class ThreadAttachFailed(override val msg: String) : NativeError(600, msg)
    data class ThreadDetachFailed(override val msg: String) : NativeError(601, msg)
    data class DeadlockDetected(override val msg: String) : NativeError(602, msg)
    
    // Allow subclasses to override message
    protected open val msg: String get() = message
    
    override fun toString(): String = "NativeError($code): $message"
    
    companion object {
        /**
         * Parse error from native "CODE: message" format.
         */
        fun fromNative(errorString: String?): NativeError? {
            if (errorString.isNullOrEmpty()) return null
            
            val parts = errorString.split(":", limit = 2)
            val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return Unknown(errorString)
            val message = parts.getOrNull(1)?.trim() ?: ""
            
            return when (code) {
                1 -> Unknown(message)
                2 -> InvalidArgument(message)
                3 -> InvalidState(message)
                4 -> NotInitialized(message)
                5 -> AlreadyInitialized(message)
                6 -> OutOfMemory(message)
                100 -> InvalidHandle(message)
                101 -> HandleNotFound(message)
                102 -> HandleAlreadyReleased(message)
                200 -> EngineNotAvailable(message)
                201 -> EngineLoadFailed(message)
                202 -> EngineInferenceFailed(message)
                203 -> EngineCancelled(message)
                300 -> ModelNotFound(message)
                301 -> ModelLoadFailed(message)
                302 -> ModelInvalidFormat(message)
                303 -> ModelVersionMismatch(message)
                400 -> AudioDecodeFailed(message)
                401 -> AudioEncodeFailed(message)
                402 -> AudioInvalidFormat(message)
                403 -> AudioFileNotFound(message)
                500 -> FFmpegNotAvailable(message)
                501 -> FFmpegOpenFailed(message)
                502 -> FFmpegDecodeFailed(message)
                503 -> FFmpegEncodeFailed(message)
                504 -> FFmpegNoAudioStream(message)
                505 -> FFmpegEncoderNotFound(message)
                600 -> ThreadAttachFailed(message)
                601 -> ThreadDetachFailed(message)
                602 -> DeadlockDetected(message)
                else -> Unknown(message.ifEmpty { "Unknown error code: $code" })
            }
        }
    }
}

