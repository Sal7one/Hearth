package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BenchmarkWorkerProtocolTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun speech() = BenchmarkCandidate("imported-local-123", "Imported Qwen", "Speech / UTTERANCE_WINDOWED",
        setOf("en", "ar", "auto"), file = File(context.filesDir, "speech-models/imported-local-123"),
        profile = SpeechProfile.QWEN3_ASR_0_6B, acceleration = "vulkan", artifactFingerprint = "a".repeat(64),
        artifactBytes = 123456, recognitionLanguage = "ar")
    private fun request(candidate: BenchmarkCandidate = speech()) = BenchmarkWorkerProtocol.Request(candidate,
        BenchmarkAudio.fromPcm16(ShortArray(16_000) { (it % 101).toShort() }), "", "نص مرجعي", "ar", "",
        emptyList(), 3, "ar", attemptTimeoutSeconds = 10)

    @After fun cleanJobs() { BenchmarkWorkerProtocol.root(context).deleteRecursively() }

    @Test fun importedCandidateAndExplicitDecoderBackendSurviveWithoutCatalogueRediscovery() {
        val original = request()
        val (id, _) = BenchmarkWorkerProtocol.create(context, original)
        val restored = BenchmarkWorkerProtocol.read(context, id)
        assertEquals(original.candidate.copy(file = original.candidate.file?.canonicalFile), restored.candidate)
        assertEquals(original.referenceText, restored.referenceText)
        assertEquals(original.clip!!.sha256, restored.clip!!.sha256)
        assertArrayEquals(original.clip.samples, restored.clip.samples)
        assertEquals(10, restored.attemptTimeoutSeconds)
    }

    @Test fun everyBundledInputRetainsItsOwnAudioReferenceAndSilenceIdentity() {
        val clip = request().clip!!
        val original = request().copy(clip = null, inputs = (0 until 32).map {
            BenchmarkInput("sample-$it", "", "reference-$it", clip, silence = it == 31)
        })
        val (id, _) = BenchmarkWorkerProtocol.create(context, original)
        val restored = BenchmarkWorkerProtocol.read(context, id)
        assertEquals(32, restored.inputs.size)
        restored.inputs.forEachIndexed { index, input ->
            assertEquals("sample-$index", input.id)
            assertEquals("reference-$index", input.reference)
            assertEquals(index == 31, input.silence)
            assertEquals(clip.sha256, input.clip!!.sha256)
        }
    }

    @Test fun translationSpecUsesTheExactPinnedCatalogueEntry() {
        val spec = TranslationCatalog.models.first()
        val candidate = BenchmarkCandidate(spec.id, spec.label, "Translation / GGUF", spec.sourceLanguages,
            spec.targetLanguages, file = File(context.filesDir, "translation-models/${spec.id}.gguf"),
            translation = spec, acceleration = "opencl", artifactFingerprint = spec.sha256)
        val original = request(candidate).copy(clip = null, text = "hello", source = "en", target = "ar")
        val (id, _) = BenchmarkWorkerProtocol.create(context, original)
        assertEquals(candidate.copy(file = candidate.file?.canonicalFile), BenchmarkWorkerProtocol.read(context, id).candidate)
        assertTrue(runCatching {
            BenchmarkWorkerProtocol.create(context, original.copy(candidate = candidate.copy(translation = spec.copy(sha256 = "b".repeat(64)))))
        }.isFailure)
    }

    @Test fun corruptedPrivateAudioCannotReachNativeInference() {
        val (id, dir) = BenchmarkWorkerProtocol.create(context, request())
        val file = File(dir, "clip.pcm")
        val bytes = file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertTrue(runCatching { BenchmarkWorkerProtocol.read(context, id) }.isFailure)
    }

    @Test fun foreignPathsOversizedPcmMetadataUnknownProfilesAndCapEscapesAreRejected() {
        assertTrue(runCatching {
            BenchmarkWorkerProtocol.create(context, request(speech().copy(file = File(context.cacheDir, "foreign.gguf"))))
        }.isFailure)
        for (cap in listOf(0, 21, 45)) assertTrue(runCatching {
            BenchmarkWorkerProtocol.create(context, request().copy(attemptTimeoutSeconds = cap))
        }.isFailure)
        val (id, dir) = BenchmarkWorkerProtocol.create(context, request())
        val file = File(dir, "request.json")
        val original = file.readText()
        val json = JSONObject(original)
        json.getJSONObject("clip").put("samples", Int.MAX_VALUE)
        file.writeText(json.toString())
        assertTrue(runCatching { BenchmarkWorkerProtocol.read(context, id) }.isFailure)
        val invalid = JSONObject(original)
        invalid.getJSONObject("candidate").put("profile", "unknown-model")
        file.writeText(invalid.toString())
        assertTrue(runCatching { BenchmarkWorkerProtocol.read(context, id) }.isFailure)
        assertTrue(runCatching { BenchmarkWorkerProtocol.directory(context, "../../outside") }.isFailure)
    }

    @Test fun workerBindingIsPrivateAndNeverRunsInTheUiProcess() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, BenchmarkWorkerService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(context.packageName + ":benchmark_worker", info.processName)
        assertFalse(BenchmarkWorkerService.isWorkerProcess(context.packageName))
    }

    @Test fun workerIdentityRejectsMainForeignUidWrongNameAndRecycledPid() {
        fun matches(pid: Int = 200, uid: Int = 10000, name: String = "hearth:benchmark_worker") =
            BenchmarkWorkerIdentity.matches(pid, uid, name, "hearth", 200, 10000, 100)
        assertTrue(matches())
        assertFalse(matches(pid = 100))
        assertFalse(matches(pid = 201))
        assertFalse(matches(uid = 10001))
        assertFalse(matches(name = "hearth"))
        assertFalse(matches(name = "other:benchmark_worker"))
    }
}
