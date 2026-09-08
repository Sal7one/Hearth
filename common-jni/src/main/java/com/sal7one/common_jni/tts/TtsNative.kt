package com.sal7one.common_jni.tts

/**
 * JNI bindings for TTS native layer.
 * 
 * Maps to functions in tts_jni.cpp
 */
internal object TtsNative {
    
    // =========================================================================
    // Engine Lifecycle
    // =========================================================================
    
    external fun nativeCreateEngine(engineType: Int): Long
    external fun nativeInitialize(handle: Long, modelPath: String, configJson: String): Boolean
    external fun nativeRelease(handle: Long)
    external fun nativeReset(handle: Long)
    external fun nativeIsInitialized(handle: Long): Boolean
    external fun nativeCancel(handle: Long)
    
    // =========================================================================
    // Synthesis
    // =========================================================================
    
    external fun nativeSynthesize(handle: Long, text: String, targetSampleRate: Int): FloatArray?
    external fun nativeStartStreaming(handle: Long): Boolean
    external fun nativePushText(handle: Long, text: String): Int
    external fun nativeFinalizeStreaming(handle: Long): Boolean
    
    // =========================================================================
    // Voice Management
    // =========================================================================
    
    external fun nativeGetVoices(handle: Long): String
    external fun nativeSetVoice(handle: Long, voiceId: String): Boolean
    external fun nativeGetCurrentVoice(handle: Long): String?
    external fun nativeLoadCustomVoice(handle: Long, voicePath: String): Boolean
    
    // =========================================================================
    // Engine Info
    // =========================================================================
    
    external fun nativeGetSampleRate(handle: Long): Int
    external fun nativeGetCapabilities(handle: Long): Int
    external fun nativeGetSequenceLength(handle: Long): Int
    external fun nativeGetRemainingCapacity(handle: Long): Int
    external fun nativeGetLastError(): String
    
    // =========================================================================
    // Engine Availability
    // =========================================================================
    
    external fun nativeIsEngineAvailable(engineType: Int): Boolean
    external fun nativeGetAvailableEngines(): IntArray
    
    init {
        System.loadLibrary("common_jni")
    }
}

