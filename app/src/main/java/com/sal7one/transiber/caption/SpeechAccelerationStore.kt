package com.sal7one.transiber.caption

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Independent per-engine choices; opening settings never probes a graphics driver. */
internal object SpeechAccelerationStore {
    private val Context.accelerationDataStore by preferencesDataStore(name = "speech_acceleration")

    fun choices(engine: CaptionEngineChoice): List<String> = when (engine) {
        CaptionEngineChoice.WHISPER, CaptionEngineChoice.COHERE, CaptionEngineChoice.FUNASR_MLT, CaptionEngineChoice.VOXTRAL -> listOf("cpu", "vulkan")
        CaptionEngineChoice.TRANSCRIBEPSY -> listOf("cpu", "vulkan", "opencl")
        else -> listOf("cpu")
    }

    fun selected(context: Context, engine: CaptionEngineChoice) =
        context.applicationContext.accelerationDataStore.data.map { prefs ->
            prefs[stringPreferencesKey(engine.name)]?.takeIf { it in choices(engine) } ?: "cpu"
        }

    suspend fun current(context: Context, engine: CaptionEngineChoice): String = selected(context, engine).first()

    suspend fun save(context: Context, engine: CaptionEngineChoice, id: String) {
        require(id in choices(engine)) { "Unsupported ${engine.name} acceleration: $id" }
        context.applicationContext.accelerationDataStore.edit { it[stringPreferencesKey(engine.name)] = id }
    }
}
