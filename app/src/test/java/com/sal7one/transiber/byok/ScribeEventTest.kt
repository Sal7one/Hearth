package com.sal7one.transiber.byok

import org.junit.Assert.*
import org.junit.Test

class ScribeEventTest {
    @Test fun revisionsStayPartialAndTimestampMetadataDoesNotDuplicateFinal() {
        assertEquals(ScribeEvent.Partial("Прив"), ScribeEvent.parse("""{"message_type":"partial_transcript","text":"Прив"}"""))
        assertEquals(ScribeEvent.Partial("Привет"), ScribeEvent.parse("""{"message_type":"partial_transcript","text":"Привет"}"""))
        assertEquals(ScribeEvent.Final("Привет"), ScribeEvent.parse("""{"message_type":"committed_transcript","text":"Привет"}"""))
        assertEquals(ScribeEvent.Ignore, ScribeEvent.parse("""{"message_type":"committed_transcript_with_timestamps","text":"Привет","words":[]}"""))
    }
    @Test fun errorMessageIsPreservedEvenWithoutAnErrorField() {
        assertEquals(ScribeEvent.Error("auth_error: Invalid API key"), ScribeEvent.parse("""{"message_type":"auth_error","message":"Invalid API key"}"""))
        assertEquals(ScribeEvent.Error("quota_exceeded: 0 credits remaining"), ScribeEvent.parse("""{"message_type":"quota_exceeded","error":"0 credits remaining"}"""))
    }
    @Test(expected = org.json.JSONException::class) fun malformedFinalCannotBecomeEmptySuccess() {
        ScribeEvent.parse("""{"message_type":"committed_transcript"}""")
    }
}
