package com.sal7one.transiber.byok

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepgramTranscriptTest {
    @Test fun finalUtteranceIncludesEarlierSegmentsAndExcludesReplacedInterim() {
        val transcript = DeepgramTranscript()
        assertEquals("First draft", transcript.accept(result("First draft")).interim)
        assertEquals("First part", transcript.accept(result("First part", stable = true)).interim)
        assertEquals("First part sec", transcript.accept(result("sec")).interim)
        assertEquals("First part second part", transcript.accept(result("second part", stable = true, end = true)).final)
        assertNull(transcript.finish())
        assertEquals("New utterance", transcript.accept(result("New utterance", stable = true, end = true)).final)
    }

    @Test fun emptyEndpointAndStreamEndFlushStableTextOnlyOnce() {
        val transcript = DeepgramTranscript()
        transcript.accept(result("Before the pause", stable = true))
        assertEquals("Before the pause", transcript.accept(result("", stable = true, end = true)).final)
        transcript.accept(result("Trailing stable text", stable = true))
        transcript.accept(result("unconfirmed"))
        assertEquals("Trailing stable text", transcript.finish())
        assertNull(transcript.finish())
    }

    private fun result(text: String, stable: Boolean = false, end: Boolean = false): JSONObject = JSONObject()
        .put("is_final", stable)
        .put("speech_final", end)
        .put("channel", JSONObject().put("alternatives", JSONArray().put(JSONObject().put("transcript", text))))
}
