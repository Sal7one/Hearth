package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BenchmarkAttemptRecordTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val candidate get()=BenchmarkCandidate("model","Model","Whisper / batch",setOf("en","ar","auto"),
        acceleration="cpu",artifactFingerprint="a".repeat(64),recognitionLanguage="auto")

    @Test fun deadlineRowsRetainExperimentAndAudioIdentityWithoutPartialScoreOrSuccess() {
        val audio=BenchmarkAudio.fromPcm16(ShortArray(32_000))
        val inputs=listOf(BenchmarkInput("english",reference="hello",clip=audio))
        val batch=BenchmarkBatch(listOf(candidate),"en","ar",attemptTimeoutSeconds=20)
        val row=BenchmarkAttemptRecord.failed(context,"run",candidate,batch,inputs,"ar","Disqualified: exceeded 20 seconds","deadline")
        assertEquals(2000L,row.audioMs);assertEquals("en",row.source);assertEquals("",row.target)
        assertEquals("automatic",row.languageControl);assertEquals("auto",row.recognitionLanguage)
        assertEquals(20,row.attemptTimeoutSeconds);assertEquals("deadline",row.terminationReason)
        assertTrue(row.computeMs.isEmpty());assertTrue(row.texts.isEmpty());assertTrue(row.samples.isEmpty())
        assertNull(row.wordErrorRate);assertNull(row.translationChrf);assertNull(BenchmarkTableModel.referenceScore100(row))
        assertFalse(BenchmarkTableModel.completed(row));assertTrue(BenchmarkTableModel.disqualified(row))
        assertEquals("hello",row.referenceText);assertEquals(BenchmarkScoring.NORMALIZATION,row.scoringNormalization)
        val completed=row.copy(error=null,terminationReason=null,texts=listOf("hello"),computeMs=listOf(1.0,1.0,1.0))
        assertEquals(BenchmarkTableModel.cohort(completed),BenchmarkTableModel.cohort(row))
        assertEquals(BenchmarkAttemptRecord.inputHash(inputs,null,"",null,"en","ar",BenchmarkTask.SPEECH),row.inputHash)
    }

    @Test fun tighteningTheCapInvalidatesOldResumeWithoutChangingAudioOrDecoderIdentity() {
        val short=BenchmarkAttemptRecord.configuration(context,candidate,3,"ar",5)
        val maximum=BenchmarkAttemptRecord.configuration(context,candidate,3,"ar",20)
        assertNotNull(short);assertNotNull(maximum);assertNotEquals(short,maximum)
        assertEquals(maximum,BenchmarkAttemptRecord.configuration(context,candidate,3,"ar",999))
        assertNotEquals(maximum,BenchmarkAttemptRecord.configuration(context,candidate.copy(recognitionLanguage="en"),3,"ar",20))
    }

    @Test fun missingInputIsRejectedBeforeAnyWorkerIsBound() {
        val failure=runCatching {BenchmarkAttemptRecord.validate(BenchmarkBatch(listOf(candidate),"en","ar"),emptyList())}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException);assertTrue(failure!!.message.orEmpty().contains("WAV"))
    }
}
