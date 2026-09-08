package com.sal7one.transiber.byok

import org.junit.Assert.*
import org.junit.Test

class SpeechModelCatalogTest {
    @Test fun mixedOpenAiCatalogFiltersChatTtsAndRealtimeConversations() {
        val models = SpeechModelCatalog.parse("""{"data":[
          {"id":"gpt-5"},{"id":"tts-1"},{"id":"gpt-audio-1.5"},{"id":"gpt-realtime-2.1"},
          {"id":"whisper-1","created":1},{"id":"gpt-4o-mini-transcribe-2025-12-15","created":5},
          {"id":"gpt-transcribe","created":6},{"id":"gpt-live-transcribe","created":7},
          {"id":"gpt-realtime-translate","created":8},{"id":"gpt-4o-transcribe-diarize","created":9}
        ]}""")
        assertEquals(6, models.size)
        assertEquals("gpt-4o-transcribe-diarize", models.first().id)
        assertEquals(listOf("gpt-transcribe", "gpt-4o-mini-transcribe-2025-12-15", "whisper-1"),
            models.filter { it.role.batchSelectable }.map { it.id })
    }
    @Test fun metadataSupportsNewProviderSpeechButNotGenericAudioChat() {
        val models = SpeechModelCatalog.parse("""{"data":[
          {"id":"vendor/new-asr","architecture":{"output_modalities":["transcription"]}},
          {"id":"vendor/chat","architecture":{"input_modalities":["audio"],"output_modalities":["text"]}},
          {"id":"groq/whisper-large-v3-turbo"}
        ]}""")
        assertEquals(listOf("groq/whisper-large-v3-turbo", "vendor/new-asr"), models.map { it.id })
    }
    @Test fun datesUseUtcUnknownLastAndStableTies() {
        val models = SpeechModelCatalog.parse("""{"data":[
          {"id":"whisper-1"},{"id":"gpt-4o-transcribe","created":86400},
          {"id":"gpt-transcribe","created":86400},{"id":"gpt-live-transcribe","created":-1}
        ]}""")
        assertEquals("1970-01-02", models.first().created)
        assertEquals("Unknown", models.last().created)
        assertEquals("gpt-4o-transcribe", models.first().id)
    }
    @Test fun missingPriceIsNotFreeAndDurationUnitsAreNeverGuessed() {
        val models = SpeechModelCatalog.parse("""{"data":[
          {"id":"whisper-1"},{"id":"gpt-transcribe","pricing":{"prompt":"0","request":"0.002","completion":"-1"}}
        ]}""")
        assertEquals("Price not provided", models.single { it.id == "whisper-1" }.pricing)
        assertEquals("input $0 · request $0.002 (billing unit not supplied)", models.first().pricing)
    }
    @Test(expected = org.json.JSONException::class)
    fun errorEnvelopeCannotBecomeEmptySuccess() { SpeechModelCatalog.parse("""{"error":{"message":"denied"}}""") }
}
