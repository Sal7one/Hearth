package com.sal7one.transiber.benchmark

import android.content.Context
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.SpeechArtifactCatalog
import com.sal7one.transiber.models.SpeechComponents
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.voice.VoiceCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BenchmarkDownloadQueueState(
    val registering: Boolean = false,
    val completed: Int = 0,
    val requested: Int = 0,
    val message: String = "",
    val errors: List<String> = emptyList(),
    val revision: Long = 0,
)

/** Registers large owner-chosen batches independently of a settings composition.
 * Actual transfer, resume, storage checks and installation remain in FileDownloads. */
internal object BenchmarkDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(BenchmarkDownloadQueueState())
    val state: StateFlow<BenchmarkDownloadQueueState> = mutable.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun enqueue(context: Context, models: List<SuggestedModel>) {
        com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model benchmark downloads")
        check(ByokPolicy.FEATURE_BYOK) { "Downloads are unavailable in the offline build; import verified models instead" }
        check(job?.isActive != true) { "A model batch is already being queued" }
        val chosen = models.distinctBy { it.id }
        require(chosen.isNotEmpty()) { "Choose at least one model" }
        val app = context.applicationContext
        mutable.value = BenchmarkDownloadQueueState(registering = true, requested = chosen.size,
            message = "Queueing ${chosen.size} model requests", revision = mutable.value.revision)
        job = scope.launch {
            val downloads = FileDownloads(app)
            val errors = mutableListOf<String>()
            var accepted = 0
            try {
                chosen.forEachIndexed { index, model ->
                    ensureActive()
                    mutable.value = mutable.value.copy(completed = index, message = "Queueing ${index + 1}/${chosen.size} · ${model.label}")
                    try {
                        enqueueModel(app, downloads, model)
                        accepted++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors += "${model.label}: ${e.message ?: e}" }
                }
            } catch (e: CancellationException) {
                errors += "Registration stopped. Previously queued downloads continue in Downloads."
                throw e
            } catch (e: Exception) {
                errors += e.message ?: e.toString()
            } finally {
                mutable.value = mutable.value.copy(registering = false, completed = accepted,
                    message = "$accepted/${chosen.size} model requests queued. Downloads continue in the background.",
                    errors = errors.toList(), revision = mutable.value.revision + 1)
            }
        }
    }

    private fun enqueueModel(context: Context, downloads: FileDownloads, model: SuggestedModel) {
        val existing = BenchmarkDownloadProgress.forModel(model, downloads.list())
        if (model.kind !in setOf("marian", "cascade", "ct2", "voice") && existing?.complete == true &&
            existing.records.singleOrNull()?.installed == false) {
            downloads.retry(existing.records.single().id)
            return
        }
        when (model.kind) {
            "speech" -> checkNotNull(SpeechArtifactCatalog.find(model.id)).let { artifact ->
                artifact.components?.let { SpeechComponents.enqueue(context, downloads, it) }
                    ?: downloads.enqueue(DownloadSpec.parse(artifact.url, artifact.fileName), true, artifact.id)
            }
            "gguf" -> checkNotNull(TranslationCatalog.models.firstOrNull { it.id == model.id }).let {
                downloads.enqueue(DownloadSpec.parse(it.url, it.fileName), true, it.id)
            }
            "marian" -> MarianPackage.enqueue(context, downloads, checkNotNull(MarianPackage.find(model.id)))
            "cascade" -> MarianCascade.enqueue(context, downloads, checkNotNull(MarianCascade.find(model.id)))
            "ct2" -> Ct2Package.enqueue(context, downloads, checkNotNull(Ct2Catalog.find(model.id)))
            "voice" -> VoiceCatalog.familyAssets(model.id).also { require(it.isNotEmpty()) { "No pinned voice assets for ${model.label}" } }
                .forEach { downloads.enqueue(DownloadSpec.parse(it.url, it.filename), true, it.id) }
            else -> error("${model.label} is configured from Models")
        }
    }
}
