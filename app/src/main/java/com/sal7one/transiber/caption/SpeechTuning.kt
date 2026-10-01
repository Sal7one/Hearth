package com.sal7one.transiber.caption

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Utterance-window tuning for offline speech engines, persisted per speech
 * profile. Ranges and the 20 ms frame rule mirror SpeechOptions validation;
 * presets are the supported "basics", custom values within range are allowed.
 */
internal data class SpeechTuning(val maxUtteranceMs: Int, val silenceMs: Int) {
    fun matches(other: SpeechTuning) = maxUtteranceMs == other.maxUtteranceMs && silenceMs == other.silenceMs
    val isStandard get() = matches(STANDARD)
    val preset: SpeechTuning? get() = PRESETS.firstOrNull { matches(it) }

    companion object {
        val STANDARD = SpeechTuning(4000, 600)
        val RESPONSIVE = SpeechTuning(2000, 400)
        val EAGER = SpeechTuning(1200, 320)
        val PRESETS = listOf(STANDARD, RESPONSIVE, EAGER)

        /** Same bounds as SpeechOptions.validate; rejects anything the runtime would refuse. */
        fun isValid(maxUtteranceMs: Int, silenceMs: Int): Boolean =
            maxUtteranceMs in 1000..15000 && maxUtteranceMs % 20 == 0 &&
                silenceMs in 200..2000 && silenceMs % 20 == 0

        fun fromPreferences(prefs: Preferences, profileId: String): SpeechTuning? {
            val window = prefs[intPreferencesKey("$profileId.window")] ?: return null
            val silence = prefs[intPreferencesKey("$profileId.silence")] ?: return null
            return if (isValid(window, silence)) SpeechTuning(window, silence) else null
        }

        fun writeInto(prefs: MutablePreferences, profileId: String, tuning: SpeechTuning?) {
            val window = intPreferencesKey("$profileId.window")
            val silence = intPreferencesKey("$profileId.silence")
            if (tuning == null || tuning.isStandard) {
                prefs.remove(window); prefs.remove(silence)
            } else {
                prefs[window] = tuning.maxUtteranceMs; prefs[silence] = tuning.silenceMs
            }
        }
    }
}

/** Stored per model profile; absent means the engine defaults (STANDARD). */
internal object SpeechTuningStore {
    private val Context.dataStore by preferencesDataStore(name = "speech_window_tuning")

    fun tuning(context: Context, profileId: String): Flow<SpeechTuning?> =
        context.applicationContext.dataStore.data.map { SpeechTuning.fromPreferences(it, profileId) }

    suspend fun current(context: Context, profileId: String): SpeechTuning =
        tuning(context, profileId).first() ?: SpeechTuning.STANDARD

    suspend fun save(context: Context, profileId: String, tuning: SpeechTuning) {
        context.applicationContext.dataStore.edit { SpeechTuning.writeInto(it, profileId, tuning) }
    }

    /** Forget the profile's override; back to the marked default. */
    suspend fun clear(context: Context, profileId: String) {
        context.applicationContext.dataStore.edit { SpeechTuning.writeInto(it, profileId, null) }
    }
}
