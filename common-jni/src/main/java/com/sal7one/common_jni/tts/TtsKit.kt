package com.sal7one.common_jni.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TtsKit — minimal high-level TTS facade.
 *
 * Only exposes the surface the app actually uses:
 *   init / isInitialized
 *   getAvailableEngines / getVoices / setVoice
 *   synthesize / synthesizeToFile  (WAV output only)
 *   cancelAll / releaseAll
 *
 * Everything else (streaming, fallback chains, playback helpers, per-engine
 * release, default-engine setters) was dead weight and has been removed.
 */
object TtsKit {

    @Volatile private var initialized = false
    private val engines = mutableMapOf<TtsEngineType, TtsEngine>()
    private val engineLock = Mutex()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                System.loadLibrary("common_jni")
            } catch (_: UnsatisfiedLinkError) {
                // Library may already be loaded by CommonJni; that's fine.
            }
            // Keep appContext unused — engines take their own Context-free paths.
            @Suppress("UNUSED_VARIABLE")
            val appCtx = context.applicationContext
            initialized = true
        }
    }

    fun isInitialized(): Boolean = initialized

    // -------------------------------------------------------------------------
    // Engine discovery
    // -------------------------------------------------------------------------

    fun getAvailableEngines(): List<TtsEngineType> = try {
        TtsNative.nativeGetAvailableEngines()
            .toList()
            .mapNotNull { TtsEngineType.fromInt(it) }
    } catch (_: Exception) {
        emptyList()
    }

    // -------------------------------------------------------------------------
    // Synthesis
    // -------------------------------------------------------------------------

    suspend fun synthesize(
        text: String,
        engineType: TtsEngineType,
        config: TtsConfig
    ): Result<TtsResult> {
        if (!initialized) {
            return Result.failure(TtsError.LibraryNotLoaded("TtsKit not initialized"))
        }
        val engine = getOrCreateEngine(engineType)
        if (!engine.isInitialized) {
            val init = engine.initialize(config)
            if (init.isFailure) return Result.failure(
                init.exceptionOrNull() ?: TtsError.InitializationFailed("Unknown error")
            )
        }
        return engine.synthesize(text)
    }

    /**
     * Synthesize and save as a WAV file. The caller owns the path — we do not
     * transcode to mp3/aac. If a different extension is passed we fail loudly
     * instead of silently writing a wav under a mismatched name.
     */
    suspend fun synthesizeToFile(
        text: String,
        outputPath: String,
        engineType: TtsEngineType,
        config: TtsConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ext = outputPath.substringAfterLast('.', "").lowercase()
        if (ext != "wav") {
            return@withContext Result.failure(
                TtsError.SynthesisFailed("Only .wav output is supported (got .$ext)")
            )
        }
        runCatching {
            val result = synthesize(text, engineType, config).getOrThrow()
            saveAsWav(result.toPcm16(), result.sampleRate, outputPath)
        }
    }

    /**
     * Persist an already-synthesized [TtsResult] as a WAV file without
     * re-running synthesis. Callers that hold a result from [synthesize]
     * must use this instead of [synthesizeToFile], which synthesizes again.
     */
    suspend fun writeResultAsWav(
        result: TtsResult,
        outputPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ext = outputPath.substringAfterLast('.', "").lowercase()
        if (ext != "wav") {
            return@withContext Result.failure(
                TtsError.SynthesisFailed("Only .wav output is supported (got .$ext)")
            )
        }
        runCatching {
            saveAsWav(result.toPcm16(), result.sampleRate, outputPath)
        }
    }

    // -------------------------------------------------------------------------
    // Voices
    // -------------------------------------------------------------------------

    suspend fun getVoices(
        engineType: TtsEngineType,
        config: TtsConfig
    ): List<TtsVoiceInfo> {
        val engine = getOrCreateEngine(engineType)
        if (!engine.isInitialized) engine.initialize(config)
        return engine.getVoices()
    }

    suspend fun setVoice(
        voiceId: String,
        engineType: TtsEngineType
    ): Result<Unit> {
        val engine = engineLock.withLock { engines[engineType] }
            ?: return Result.failure(TtsError.NotInitialized("Engine not created"))
        return engine.setVoice(voiceId)
    }

    // -------------------------------------------------------------------------
    // Cancellation / cleanup
    // -------------------------------------------------------------------------

    fun cancelAll() {
        // cancel() is synchronous and cheap; snapshot under the JVM monitor to
        // avoid suspending here (cancelAll must be callable from non-suspend code).
        val snapshot: List<TtsEngine> = synchronized(engines) { engines.values.toList() }
        snapshot.forEach { it.cancel() }
    }

    suspend fun releaseAll() {
        val snapshot: List<TtsEngine> = engineLock.withLock {
            val all = engines.values.toList()
            engines.clear()
            all
        }
        // Release outside the lock — engine.release() is suspending.
        snapshot.forEach { runCatching { it.release() } }
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private suspend fun getOrCreateEngine(type: TtsEngineType): TtsEngine =
        engineLock.withLock {
            engines.getOrPut(type) { BaseTtsEngine(type) }
        }

    private fun saveAsWav(pcm: ShortArray, sampleRate: Int, outputPath: String) {
        val file = File(outputPath)
        file.parentFile?.mkdirs()

        val dataSize = pcm.size * 2
        val fileSize = dataSize + 36

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intLE(fileSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intLE(16))                 // Subchunk1Size
            fos.write(shortLE(1))                // PCM
            fos.write(shortLE(1))                // mono
            fos.write(intLE(sampleRate))
            fos.write(intLE(sampleRate * 2))     // byte rate
            fos.write(shortLE(2))                // block align
            fos.write(shortLE(16))               // bits/sample
            fos.write("data".toByteArray())
            fos.write(intLE(dataSize))

            val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { buffer.putShort(it) }
            fos.write(buffer.array())
        }
    }

    private fun intLE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLE(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
