package com.sal7one.transiber.caption

import android.app.Application
import android.os.Build
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelDigestAlgorithm
import com.sal7one.common_jni.model.ModelDigestKind
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.translation.MarianPackage
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CaptionTranslationReadinessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun local(id: String) = CaptionOverlayConfig(engine = CaptionEngineChoice.QWEN,
        mode = CaptionMode.TRANSLATE, localTranslationEnabled = true, textTranslationProviderId = "local",
        localTranslationModelId = id, streamLanguage = "en", target = TranslationTarget.ARABIC)

    private suspend fun readiness(config: CaptionOverlayConfig, mode: SttMode = SttMode.BATCH,
                                  model: CaptionLanguageModel = CaptionLanguageModel(config.modelId)) =
        readCaptionTranslationReadiness { checkCaptionTranslationSetup(context, config, mode, model) }

    @Test fun small100AndQuickMtCanBecomeReadyAfterRepairWithoutChangingSelectedId() = runBlocking {
        arm64()
        for (id in listOf("ct2-small100-int8", "ct2-quickmt-en-ar")) {
            val spec = checkNotNull(Ct2Catalog.find(id))
            val root = Ct2Package.directory(context, spec)
            root.deleteRecursively()
            try {
                val selected = local(id)
                val absent = readiness(selected)
                assertFalse(absent.ready)
                assertTrue(absent.detail, absent.detail.contains("Install"))
                root.mkdirs()
                // Sparse files model a previously verified install's presence. These are not inference fixtures.
                spec.files.forEach { file -> sparse(File(root, file.name), file.bytes) }
                assertTrue(readiness(selected).ready)
                val first = spec.files.first()
                sparse(File(root, first.name), first.bytes - 1)
                assertFalse(readiness(selected).ready)
                sparse(File(root, first.name), first.bytes)
                assertTrue(readiness(selected).ready)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun unsupportedPairIsExplainedSeparatelyFromMissingModel() = runBlocking {
        arm64()
        val result = readiness(local("ct2-quickmt-en-ar").copy(streamLanguage = "ru"))
        assertFalse(result.ready)
        assertTrue(result.detail, result.detail.contains("does not support ru → ar"))
        assertFalse(result.detail.contains("Choose a local translator"))
    }

    @Test fun registeredMarianMustRetainAllFourPrivateComponentsAndCanRecoverWithoutReselection() = runBlocking {
        val pair = checkNotNull(MarianPackage.find("marian-en-ar"))
        val root = File(context.filesDir, "readiness-marian-fixture").apply { mkdirs() }
        val prefs = context.getSharedPreferences("model_registry", android.content.Context.MODE_PRIVATE)
        val previousRecords = prefs.getString("registered_models", null)
        val previousInstance = ReflectionHelpers.getStaticField<ModelRegistry?>(ModelRegistry::class.java, "instance")
        val record = ModelRegistry.RegisteredModel("fixture-marian", "Verified install fixture", ModelEngineType.TRANSLATE,
            ModelRegistry.ModelSource.INTERNAL, root.path, pair.downloadBytes, true, true, 0,
            ModelDigest(ModelDigestAlgorithm.SHA256, ModelDigestKind.TREE, pair.treeSha256, ModelDigestTrust.PINNED))
        prefs.edit().putString("registered_models", JSONArray().put(record.toJson()).toString()).commit()
        ReflectionHelpers.setStaticField(ModelRegistry::class.java, "instance", null)
        try {
            val selected = local(pair.id)
            val empty = readiness(selected)
            assertFalse(empty.ready)
            assertTrue(empty.detail, empty.detail.contains("required model files are missing or incomplete"))
            pair.parts.forEach { sparse(File(root, it.fileName), it.bytes) }
            assertTrue(readiness(selected).ready)
            File(root, pair.parts.last().fileName).delete()
            assertFalse(readiness(selected).ready)
            pair.parts.last().let { sparse(File(root, it.fileName), it.bytes) }
            assertTrue(readiness(selected).ready)
        } finally {
            root.deleteRecursively()
            prefs.edit().apply {
                if (previousRecords == null) remove("registered_models") else putString("registered_models", previousRecords)
            }.commit()
            ReflectionHelpers.setStaticField(ModelRegistry::class.java, "instance", previousInstance)
        }
    }

    @Test fun unsupportedProcessReportsRuntimeInsteadOfPretendingWeightsAreMissing() = runBlocking {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("x86_64"))
        ShadowVMRuntime.setIs64Bit(true)
        val result = readiness(local("ct2-small100-int8"))
        assertFalse(result.ready)
        assertTrue(result.detail, result.detail.contains("ARM64 runtime"))
    }

    @Test fun unknownSpeechLanguageIsNotMisreportedAsMissingTranslatorAndFixedMetadataCounts() = runBlocking {
        arm64()
        val selected = local("ct2-quickmt-en-ar").copy(engine = CaptionEngineChoice.WHISPER, streamLanguage = "auto")
        val multilingual = readiness(selected, model = CaptionLanguageModel("multilingual", whisperVocabulary = 51865))
        assertFalse(multilingual.ready)
        assertTrue(multilingual.detail, multilingual.detail.contains("known spoken language"))
        val fixed = readiness(selected, model = CaptionLanguageModel("english", whisperVocabulary = 51864))
        assertFalse(fixed.ready)
        assertTrue("Verified English weights must pass source validation and reach the actual missing-install check: ${fixed.detail}", fixed.detail.contains("Install"))
        val vosk = readiness(selected.copy(engine = CaptionEngineChoice.VOSK), model = CaptionLanguageModel("pinned-vosk", fixedSourceLanguage = "en"))
        assertTrue(vosk.detail, vosk.detail.contains("Install"))
    }

    @Test fun allTextOnlyCloudCallbacksRequireAKnownSourceWhileStructuredSonioxCanReportIt() = runBlocking {
        SttMode.entries.forEach { mode ->
            assertEquals(mode != SttMode.STREAMING_SONIOX, captionRequiresKnownTranslationSource(CaptionEngineChoice.CLOUD, mode))
        }
        val result = readiness(local("ct2-small100-int8").copy(engine = CaptionEngineChoice.CLOUD, streamLanguage = "auto"), SttMode.STREAMING_ASSEMBLYAI)
        assertFalse(result.ready)
        assertTrue(result.detail, result.detail.contains("AssemblyAI"))
        assertTrue(result.detail, result.detail.contains("changing the translation model will not fix"))
    }

    @Test fun pluginsWithoutReliableLanguageMetadataNeedAnExplicitSupportedSource() = runBlocking {
        for (engine in listOf(CaptionEngineChoice.TRANSCRIBEPSY, CaptionEngineChoice.OMNILINGUAL, CaptionEngineChoice.VOXTRAL)) {
            val result = readiness(local("ct2-small100-int8").copy(engine = engine, streamLanguage = "auto"))
            assertFalse(result.ready)
            assertTrue("$engine: ${result.detail}", result.detail.contains("known spoken language"))
        }
        assertFalse(captionRequiresKnownTranslationSource(CaptionEngineChoice.QWEN, SttMode.BATCH))
        assertFalse(captionRequiresKnownTranslationSource(CaptionEngineChoice.NEMOTRON, SttMode.BATCH))
    }

    @Test fun originalAndIntegratedCaptionsNeverRequireRememberedLocalWeights() = runBlocking {
        val selected = local("missing-model")
        assertTrue(readiness(selected.copy(mode = CaptionMode.CAPTIONS)).ready)
        assertTrue(readiness(selected.copy(engine = CaptionEngineChoice.CLOUD, textTranslationProviderId = "", localTranslationEnabled = false), SttMode.STREAMING_OPENAI).ready)
    }

    @Test fun cancellationPropagatesAndNativeLinkErrorsAreReported() = runBlocking {
        assertThrows(CancellationException::class.java) {
            runBlocking { readCaptionTranslationReadiness { throw CancellationException("new selection") } }
        }
        val result = readCaptionTranslationReadiness { throw UnsatisfiedLinkError("missing CT2 library") }
        assertFalse(result.ready)
        assertTrue(result.detail, result.detail.contains("missing CT2 library"))
    }

    private fun arm64() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        ShadowVMRuntime.setIs64Bit(true)
    }
    private fun sparse(file: File, bytes: Long) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }
}
