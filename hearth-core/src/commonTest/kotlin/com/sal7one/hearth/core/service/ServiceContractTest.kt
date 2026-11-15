package com.sal7one.hearth.core.service

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ServiceContractTest {
    private val speech = EngineSelection("whisper-local", "whisper-tiny-q5_1", "/models/tiny.bin")
    private val translation = EngineSelection("marian-local", "ct2-quickmt-en-ar", "/models/quickmt")
    private val voice = EngineSelection("supertonic-local", "supertonic-3", "/models/supertonic")
    private val transcribe = ServiceRoute("transcribe", ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech), setOf("auto", "en"), maxAudioSeconds = 600)
    private val translate = ServiceRoute("translate-en-ar", ServiceOperation.TRANSLATE,
        PipelineConfig(translation = translation, sourceLanguage = "en", targetLanguage = "ar"), setOf("en"), setOf("ar"), maxTextCharacters = 100)
    private val speak = ServiceRoute("speak", ServiceOperation.SPEAK, PipelineConfig(voice = voice, sourceLanguage = "en", voiceId = "F1"), setOf("en", "ar"), voices = setOf("F1", "M1"))

    private fun refusal(block: () -> Unit) = assertFailsWith<ServiceException> { block() }.code

    @Test fun aRouteConfiguresExactlyTheStagesOfItsOperation() {
        assertFailsWith<IllegalArgumentException> { ServiceRoute("x", ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech, voice = voice), setOf("en")) }
        assertFailsWith<IllegalArgumentException> { ServiceRoute("x", ServiceOperation.TRANSLATE, PipelineConfig(translation = translation), setOf("en")) }
        assertFailsWith<IllegalArgumentException> { ServiceRoute("x", ServiceOperation.SPEAK, PipelineConfig(voice = voice), setOf("auto")) }
        assertFailsWith<IllegalArgumentException> { ServiceRoute("x", ServiceOperation.PIPELINE, PipelineConfig(speech = speech), setOf("en")) }
        assertFailsWith<IllegalArgumentException> { ServiceRoute("Not An Id", ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech), setOf("en")) }
        assertFailsWith<IllegalArgumentException> { ServiceRoute("x", ServiceOperation.TRANSCRIBE, PipelineConfig(speech = speech), setOf("en"), maxAudioSeconds = 3_601) }
        ServiceRoute("pipeline", ServiceOperation.PIPELINE, PipelineConfig(speech = speech, translation = translation), setOf("auto"), setOf("ar"))
    }

    @Test fun aRequestMayOnlyAskForWhatItsRouteLists() {
        assertEquals("en", ServiceContract.resolve(ServiceJobRequest("transcribe", sourceLanguage = "en"), transcribe).sourceLanguage)
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("transcribe", sourceLanguage = "fr"), transcribe) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("transcribe", text = "typed"), transcribe) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("transcribe", targetLanguage = "ar"), transcribe) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("transcribe", voice = "F1"), transcribe) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("other"), transcribe) })

        assertEquals("ar", ServiceContract.resolve(ServiceJobRequest("translate-en-ar", text = "Hello"), translate).targetLanguage)
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("translate-en-ar"), translate) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("translate-en-ar", text = "  "), translate) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("translate-en-ar", text = "x".repeat(101)), translate) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("translate-en-ar", text = "Hello", targetLanguage = "fr"), translate) })

        val spoken = ServiceContract.resolve(ServiceJobRequest("speak", text = "مرحبا", sourceLanguage = "ar", voice = "M1"), speak)
        assertEquals("ar", spoken.sourceLanguage); assertEquals("M1", spoken.voiceId)
        assertEquals("F1", ServiceContract.resolve(ServiceJobRequest("speak", text = "Hello"), speak).voiceId)
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("speak", text = "Hello", voice = "Unknown"), speak) })
        assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.resolve(ServiceJobRequest("speak", text = "Hello", deadlineSeconds = 0), speak) })
    }

    @Test fun keysBoundsAndDeadlinesAreEnforcedBeforeAnyWork() {
        ServiceContract.requireKey("0123456789abcdef0123456789abcdef")
        for (key in listOf("", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcde", "../0123456789abcdef0123456789ab")) {
            assertEquals(ServiceErrorCode.INVALID_REQUEST, refusal { ServiceContract.requireKey(key) })
        }
        ServiceContract.requireAudioLength(600.0, transcribe)
        assertEquals(ServiceErrorCode.INPUT_TOO_LONG, refusal { ServiceContract.requireAudioLength(600.5, transcribe) })
        assertEquals(ServiceErrorCode.UNSUPPORTED_MEDIA, refusal { ServiceContract.requireAudioLength(0.0, transcribe) })
        assertEquals(ServiceErrorCode.UNSUPPORTED_MEDIA, refusal { ServiceContract.requireAudioLength(Double.NaN, transcribe) })

        assertNull(ServiceContract.estimateSeconds(312.4, null), "An unmeasured route has no estimate")
        assertEquals(25, ServiceContract.estimateSeconds(312.4, 0.08))
        assertEquals(1, ServiceContract.estimateSeconds(1.0, 0.01))
        ServiceContract.requireWithinDeadline(25, 780); ServiceContract.requireWithinDeadline(null, 1); ServiceContract.requireWithinDeadline(25, null)
        assertEquals(ServiceErrorCode.WOULD_EXCEED_DEADLINE, refusal { ServiceContract.requireWithinDeadline(781, 780) })
    }

    @Test fun fingerprintsSeparateEveryFieldForIdempotentCreation() {
        val request = ServiceJobRequest("translate-en-ar", text = "Hello", targetLanguage = "ar")
        assertEquals(request.fingerprint(), request.copy().fingerprint())
        assertNotEquals(request.fingerprint(), request.copy(text = "Hello ").fingerprint())
        assertNotEquals(request.fingerprint(), request.copy(requireResident = true).fingerprint())
        assertNotEquals(request.fingerprint(), request.copy(deadlineSeconds = 60).fingerprint())
        // A value cannot move between adjacent fields and keep the same identity.
        assertNotEquals(ServiceJobRequest("r", sourceLanguage = "en", targetLanguage = null).fingerprint(),
            ServiceJobRequest("r", sourceLanguage = null, targetLanguage = "en").fingerprint())
        assertNotEquals(ServiceJobRequest("r", voice = "a;1:b", text = "c").fingerprint(), ServiceJobRequest("r", voice = "a", text = "b;1:c").fingerprint())
    }

    @Test fun webVttCarriesFinalSegmentsInOrderAndEscapesMarkup() {
        val vtt = ServiceContract.webVtt(listOf(
            CaptionSegment("1", "The museum opens at nine.", startMs = 0, endMs = 2_400),
            CaptionSegment("2", "partial", final = false, startMs = 2_400, endMs = 2_500),
            CaptionSegment("3", "Bring <your> ticket & map.", translation = "أحضر تذكرتك.", startMs = 3_661_005, endMs = 3_661_005),
            CaptionSegment("4", "   ", startMs = 5_000, endMs = 6_000),
        ))
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.400\nThe museum opens at nine.\n\n01:01:01.005 --> 01:01:01.006\nأحضر تذكرتك.\n", vtt)
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:00.001\na &lt;b&gt; &amp; c\n", ServiceContract.webVtt(listOf(CaptionSegment("1", "a <b> & c"))))
        assertEquals("WEBVTT\n", ServiceContract.webVtt(emptyList()))
    }
}
