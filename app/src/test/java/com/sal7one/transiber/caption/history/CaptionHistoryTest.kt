package com.sal7one.transiber.caption.history

import com.sal7one.transiber.caption.*
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionHistoryTest {
    private fun line(id: Long, original: String = "Hello", translation: String = "") = SavedCaption(id, 100,
        original, translation, "en", "ar".takeIf { translation.isNotEmpty() }, "Nemotron", "MIC")

    @Test fun defaultsOffAndPersistsAcrossOrientations() {
        val prefs = mutablePreferencesOf()
        assertFalse(CaptionOverlayConfig().saveCaptionHistory)
        assertFalse(CaptionConfigStore.readFrom(prefs).saveCaptionHistory)
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(saveCaptionHistory = true), OverlayOrientation.PORTRAIT)
        assertTrue(CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE).saveCaptionHistory)
        CaptionConfigStore.writeInto(prefs, CaptionOverlayConfig(saveCaptionHistory = false), OverlayOrientation.LANDSCAPE)
        assertFalse(CaptionConfigStore.readFrom(prefs).saveCaptionHistory)
    }
    @Test fun emptyFinalsAndUnownedTranslationsDoNotCreateHistory() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        assertNull(capture.observe(line(1, "   "), true))
        assertNull(capture.translation(2, "late", "ar"))
        assertNull(capture.translation(1, "   ", "ar"))
    }
    @Test fun oversizedTextIsRejectedBeforeEnteringIoQueue() = runTest {
        val errors = mutableListOf<String>()
        val recorder = CaptionHistoryRecorder(this, { _, _ -> fail("Oversized text reached storage") }, errors::add)
        recorder.setEnabled(true); recorder.observe(line(1, "x".repeat(64001)))
        recorder.close(); advanceUntilIdle()
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("64000"))
        assertThrows(IllegalArgumentException::class.java) { line(2).copy(engine = "x".repeat(1025)).validate() }
    }
    @Test fun enablingDoesNotBackfillDisplayedFinalsOrInProgressCloudRuns() {
        val capture = CaptionHistoryCapture()
        assertNull(capture.observe(line(1), true))
        assertNull(capture.observe(line(2), false))
        capture.setEnabled(true)
        assertNull(capture.observe(line(1, "corrected old line"), true))
        assertNull(capture.observe(line(2, "final accumulated text"), true))
        assertNotNull(capture.observe(line(3), true))
    }
    @Test fun partialsNeverSaveAndCorrectionsKeepOneIdentity() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        assertNull(capture.observe(line(1, "partial"), false))
        val first = capture.observe(line(1, "final"), true)!!
        val revised = capture.observe(line(1, "corrected"), true)!!
        assertEquals(first.sessionId, revised.sessionId)
        assertEquals(first.line.id, revised.line.id)
        assertEquals("corrected", first.line.merge(revised.line).original)
    }
    @Test fun offInvalidatesQueuedWritesAndLateTranslationEvenAfterOn() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        val old = capture.observe(line(1), true)!!
        capture.setEnabled(false)
        assertFalse(capture.mayWrite(old))
        assertNull(capture.translation(1, "مرحبا", "ar"))
        capture.setEnabled(true)
        assertFalse(capture.mayWrite(old))
        assertNull(capture.observe(line(1, "correction"), true))
        assertNull(capture.translation(1, "مرحبا", "ar"))
        assertNotEquals(old.sessionId, capture.observe(line(2), true)!!.sessionId)
    }
    @Test fun clearStartsFreshSessionAndOldTranslationsCannotResurrectIt() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        val before = capture.observe(line(1), true)!!
        capture.boundary()
        assertTrue("Already accepted finals still drain", capture.mayWrite(before))
        assertNull(capture.translation(1, "late", "ar"))
        assertNull(capture.observe(line(1, "stale correction"), true))
        assertNotEquals(before.sessionId, capture.observe(line(2), true)!!.sessionId)
    }
    @Test fun translationUpdatesSavedLineAfterDisplayTailEvictionWithoutErasingOriginal() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        val saved = capture.observe(line(1), true)!!
        repeat(100) { capture.observe(line(it.toLong() + 2), true) }
        val translated = capture.translation(1, "مرحبا", "ar")!!
        val merged = saved.line.merge(translated.line)
        assertEquals("Hello", merged.original)
        assertEquals("مرحبا", merged.translation)
        assertEquals(saved.line.created, merged.created)
        assertEquals(saved.sessionId, translated.sessionId)
    }
    @Test fun boundedIdentityWindowRejectsOldCorrectionsAndSplitsLongSessions() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        val first = capture.observe(line(0), true)!!
        var last = first
        repeat(CaptionHistoryCapture.MAX_TRACKED_LINES) { last = capture.observe(line(it.toLong() + 1), true)!! }
        assertNull(capture.translation(0, "late", "ar"))
        assertNull(capture.observe(line(0), true))
        assertNotEquals(first.sessionId, last.sessionId)
    }
    @Test fun deleteAllVersionFollowsOriginalOwnershipRatherThanLateCallbackTime() {
        var version = 0L
        val capture = CaptionHistoryCapture(deletions = { version }).apply { setEnabled(true) }
        val original = capture.observe(line(1), true)!!
        version++
        val late = capture.translation(1, "late", "ar")!!
        assertEquals(original.deletionVersion, late.deletionVersion)
        assertNotEquals(version, late.deletionVersion)
        assertEquals(version, capture.observe(line(2), true)!!.deletionVersion)
    }
    @Test fun translatedOnlyRoutesNeverInventOriginalCc() {
        for (route in CaptionTranslationRoute.entries) {
            val output = captionHistoryText(1, "Hello", 100, route, false, "ar", "auto", "test", "MIC")
            assertEquals(if (route.sttToEnglish) "" else "Hello", output.original)
            assertEquals(if (route.sttToEnglish) "Hello" else "", output.translation)
            assertNull(output.sourceLanguage)
            assertEquals(if (route.sttToEnglish) "en" else null, output.translationLanguage)
        }
        val gpt = captionHistoryText(2, "مرحبا", 100, CaptionTranslationRoute.LIVE_TARGET, true,
            "ar", "en", "OpenAI Translate", "PLAYBACK_CAPTURE")
        assertEquals("", gpt.original)
        assertEquals("مرحبا", gpt.translation)
        assertEquals("ar", gpt.translationLanguage)
    }
    @Test fun englishPivotReplacesIntermediaryTranslationWithoutFabricatingOriginal() {
        val pivot = captionHistoryText(1, "Hello", 100, CaptionTranslationRoute.ENGLISH_PIVOT, false,
            "ar", "zh", "Whisper", "MIC")
        val final = pivot.merge(SavedCaption(1, 200, translation = "مرحبا", translationLanguage = "ar"))
        assertEquals("", final.original)
        assertEquals("مرحبا", final.translation)
        assertEquals("ar", final.translationLanguage)
    }
    @Test fun sourceAndTranslatedRunsRemainIndependentForUnalignedProviderOutput() {
        val capture = CaptionHistoryCapture().apply { setEnabled(true) }
        val source = capture.observe(line(1), true)!!
        val mt = capture.observe(line(2, "", "مرحبا"), true)!!
        assertEquals(source.sessionId, mt.sessionId)
        assertNotEquals(source.line.id, mt.line.id)
        assertEquals("", mt.line.original)
        assertEquals("", source.line.translation)
    }
    @Test fun exportPreservesUnicodeAndMakesUnavailableOriginalExplicit() {
        val lines = listOf(line(1, "你好 😀", "مرحبا"), line(2, "", "ترجمة فقط"))
        val saved = SavedCaptionSession(CaptionSessionSummary("session", "Meeting", 100, 200, 2, ""), lines)
        val json = JSONObject(saved.toJson())
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("你好 😀", json.getJSONArray("lines").getJSONObject(0).getString("original"))
        assertTrue(json.getJSONArray("lines").getJSONObject(1).isNull("original"))
        assertFalse(json.has("audio"))
        assertFalse(json.has("apiKey"))
        val text = saved.toText("CC", "Translation", "Unavailable")
        assertTrue(text.contains("CC (en): Unavailable"))
        assertTrue(text.contains("Translation (ar): ترجمة فقط"))
    }
    @Test fun offCancelsWaitingIoWritesAndOnDoesNotReplayThem() = runTest {
        val saved = mutableListOf<CaptionHistoryWrite>()
        val recorder = CaptionHistoryRecorder(this, { item, permitted -> if (permitted()) saved += item }, { fail(it) })
        recorder.setEnabled(true)
        recorder.observe(line(1))
        recorder.setEnabled(false)
        recorder.setEnabled(true)
        recorder.observe(line(2))
        recorder.close(); advanceUntilIdle()
        assertEquals(listOf(2L), saved.map { it.line.id })
    }
    @Test fun stopDrainsAcceptedFinalsButDoesNotAcceptNewCallbacks() = runTest {
        val saved = mutableListOf<CaptionHistoryWrite>()
        val recorder = CaptionHistoryRecorder(this, { item, permitted -> if (permitted()) saved += item }, { fail(it) })
        recorder.setEnabled(true); recorder.observe(line(1)); recorder.close()
        recorder.observe(line(2)); advanceUntilIdle()
        assertEquals(listOf(1L), saved.map { it.line.id })
        assertTrue(recorder.job.isCompleted)
    }
    @Test fun toggleOffDuringStorageRechecksConsentBeforeCommit() = runTest {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val saved = mutableListOf<Long>()
        val recorder = CaptionHistoryRecorder(this, { item, permitted ->
            started.complete(Unit); finish.await(); if (permitted()) saved += item.line.id
        }, { fail(it) })
        recorder.setEnabled(true); recorder.observe(line(1)); runCurrent()
        assertTrue(started.isCompleted)
        recorder.setEnabled(false); finish.complete(Unit); recorder.close(); advanceUntilIdle()
        assertTrue(saved.isEmpty())
    }
    @Test fun storageErrorStopsSavingAndReportsActualReasonWithoutCrashingSpeechCaller() = runTest {
        val errors = mutableListOf<String>(); val attempted = mutableListOf<Long>()
        val recorder = CaptionHistoryRecorder(this, { item, _ -> attempted += item.line.id; error("disk full") }, errors::add)
        recorder.setEnabled(true); recorder.observe(line(1)); recorder.observe(line(2)); runCurrent()
        recorder.observe(line(3)); recorder.close(); advanceUntilIdle()
        assertEquals(listOf(1L), attempted)
        assertEquals(listOf("disk full"), errors)
        assertEquals("disk full", recorder.error)
    }
    @Test fun queueOverflowIsBoundedVisibleAndDropsNothingSilently() = runTest {
        val errors = mutableListOf<String>(); val saved = mutableListOf<Long>()
        val recorder = CaptionHistoryRecorder(this, { item, allowed -> if (allowed()) saved += item.line.id }, errors::add)
        recorder.setEnabled(true)
        repeat(65) { recorder.observe(line(it.toLong())) }
        recorder.close(); advanceUntilIdle()
        assertTrue(saved.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("queue is full"))
    }
    @Test fun deliberateOffOnRetriesAfterFailure() = runTest {
        val saved = mutableListOf<Long>(); var failing = true
        val recorder = CaptionHistoryRecorder(this, { item, allowed ->
            if (failing) error("storage failure") else if (allowed()) saved += item.line.id
        }, {})
        recorder.setEnabled(true); recorder.observe(line(1)); runCurrent()
        failing = false; recorder.setEnabled(false); recorder.setEnabled(true); recorder.observe(line(2))
        recorder.close(); advanceUntilIdle()
        assertEquals(listOf(2L), saved)
        assertNull(recorder.error)
    }
}
