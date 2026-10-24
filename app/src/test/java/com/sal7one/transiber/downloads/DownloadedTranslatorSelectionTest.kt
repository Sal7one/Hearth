package com.sal7one.transiber.downloads

import android.app.Application
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.translation.LocalTranslationModels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DownloadedTranslatorSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val spec get() = TranslationCatalog.models.minBy { it.bytes }
    private val models get() = LocalTranslationModels(File(context.filesDir, "translation-models"))
    private val item get() = FileDownload(-7, spec.fileName, 8, 0, spec.bytes, spec.bytes,
        phase = "Installed", installedId = spec.id, modelId = spec.id)

    @Before fun reset() = runBlocking {
        UserPreferences.initialize(context)
        context.getSharedPreferences("file_downloads", 0).edit().clear().commit()
        CaptionConfigStore.update(context) { CaptionOverlayConfig(engine = CaptionEngineChoice.WHISPER,
            modelId = "selected-speech", streamLanguage = "en", target = TranslationTarget.ARABIC,
            mode = CaptionMode.CAPTIONS, textTranslationProviderId = "deepl",
            localTranslationModelId = "old-local-model", minimalHeader = true) }
        models.file(spec).delete()
        Unit
    }

    @Test fun useLocalDownloadReplacesTheCloudStageAndPersistsTheChoiceWithoutChangingSpeechOrAppearance() = runBlocking {
        val before = CaptionConfigStore.config(context).first()
        val file = models.file(spec).apply { parentFile!!.mkdirs() }
        // Presence-only UI check; publisher hashes are still required when opening inference.
        RandomAccessFile(file, "rw").use { it.setLength(spec.bytes) }
        try {
            FileDownloads(context).selectInstalled(item)
            val saved = CaptionConfigStore.config(context).first()
            assertEquals(before.copy(localTranslationModelId = spec.id, textTranslationProviderId = "local",
                localTranslationEnabled = true, mode = CaptionMode.TRANSLATE), saved)
            assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,
                captionTranslationRoute(saved, com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH))
        } finally { file.delete() }
    }

    @Test fun staleInstalledRowCannotSelectAMissingOrTruncatedPrivateCopy() = runBlocking {
        val before = CaptionConfigStore.config(context).first()
        val file = models.file(spec)
        for (truncated in listOf(false, true)) {
            if (truncated) { file.parentFile!!.mkdirs(); file.writeText("incomplete") }
            try {
                FileDownloads(context).selectInstalled(item)
                fail("A stale download phase must not select absent model weights")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Download or import"))
            } finally { file.delete() }
            assertEquals(before, CaptionConfigStore.config(context).first())
        }
    }

    @Test fun mismatchedDownloadCannotSelectADifferentInstalledTranslator() = runBlocking {
        val before = CaptionConfigStore.config(context).first()
        context.getSharedPreferences("file_downloads", 0).edit().putString("download_-7",
            JSONObject().put("model", "another-model").toString()).commit()
        try {
            FileDownloads(context).selectInstalled(item)
            fail("The Use action must keep the journal's model identity")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("does not match"))
        }
        assertEquals(before, CaptionConfigStore.config(context).first())
    }
}
