package com.sal7one.transiber.runtime

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.translation.ConversationTranslatorSnapshot
import com.sal7one.transiber.translation.TranslationOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise production entry points with stale TV state, before files or providers can open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TvLocalModelBoundariesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val model get() = TranslationCatalog.find("eurollm-17b-q4")

    @Before fun televisionWithNoModelFiles() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEVISION, true)
        assertFalse(LocalModelPolicy.allowed(context))
        context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tvDenied(action: () -> Unit) {
        val failure = assertThrows(IllegalStateException::class.java, action)
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Local models are disabled on TVs"))
    }

    /** FOSS must reject at its network gate even before TV-specific transfer handling. */
    private fun transferDenied(action: () -> Unit) {
        val failure = assertThrows(IllegalStateException::class.java, action)
        if (ByokPolicy.FEATURE_BYOK) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Local models are disabled on TVs"))
        } else {
            // Legacy retry() has an unlabelled check; both errors must occur before worker/file access.
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("offline build") || failure.message == "Check failed.")
        }
    }

    @Test fun staleSavedTranslatorCannotOpenAnyLocalRuntime() {
        for (id in listOf(model.id, TranslationOptions.MARIAN_EN_AR, TranslationOptions.ML_KIT)) {
            tvDenied { ConversationTranslatorSnapshot(id).open(context) }
        }
    }

    @Test fun modelEnqueueFailsBeforeFolderChecksOrJournalWrites() {
        val downloads = FileDownloads(context)
        transferDenied { downloads.enqueue(DownloadSpec.parse(model.url, model.fileName), true, model.id) }
        assertTrue(context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun staleModelRetryCannotStartAWorkerOrChangeItsPausedPhase() {
        val record = JSONObject().put("id", -7L).put("model", model.id)
            .put("modelsFolder", true).put("phase", "Paused")
            .put("url", model.url).put("title", model.fileName)
        val prefs = context.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)
        prefs.edit().putString("download_-7", record.toString()).putStringSet("public_ids", setOf("-7")).commit()
        val downloads = FileDownloads(context)
        transferDenied { downloads.retry(-7L) }
        assertEquals("Paused", JSONObject(prefs.getString("download_-7", "")!!).getString("phase"))
    }

    @Test fun installRejectsBeforeResolvingTheMissingDownloadOrOpeningAStream() {
        val downloads = FileDownloads(context)
        // No matching download exists: reaching uri() or package lookup would fail with a different error.
        tvDenied { runBlocking { downloads.installModel(-8L, model.id) } }
        transferDenied { runBlocking { downloads.installTranslation(-8L, model) } }
    }

    @Test fun registryImportsRejectBeforeInspectingFilesOrResolvingProviders() {
        val registry = ModelRegistry.getInstance(context)
        tvDenied { runBlocking { registry.registerModel("/missing-tv-model.gguf") } }
        tvDenied { runBlocking { registry.importModel(Uri.parse("content://unavailable/model")) } }
        tvDenied { runBlocking { registry.importModelDirectory(Uri.parse("content://unavailable/tree/model")) } }
        var populated = false
        tvDenied {
            runBlocking {
                registry.registerPinnedDirectory("must-not-stage", "0".repeat(64)) { populated = true }
            }
        }
        assertFalse("TV policy must run before staging opens a component stream", populated)
    }
}
