package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.data.ModelInstallPhase
import com.sal7one.hearth.desktop.data.ModelInstallProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path

internal data class ModelTransfer(val id: String, val downloaded: Long = 0, val total: Long = 0,
    val phase: ModelInstallPhase = ModelInstallPhase.DOWNLOADING, val queued: Boolean = false,
    val importing: Boolean = false, val cancelling: Boolean = false, val component: String = "") {
    val checking: Boolean get() = phase == ModelInstallPhase.CHECKING || phase == ModelInstallPhase.VERIFYING
    val label: String get() = when {
        cancelling -> "Cancelling"
        queued -> "Queued"
        importing -> "Checking import"
        phase == ModelInstallPhase.CHECKING -> "Checking existing files"
        phase == ModelInstallPhase.EXTRACTING -> "Unpacking"
        phase == ModelInstallPhase.VERIFYING -> "Verifying"
        else -> "Downloading"
    }
}
internal data class ModelLibraryView(val transfers: Map<String, ModelTransfer> = emptyMap(), val refreshing: Boolean = false,
    val notice: String? = null, val verifiedImports: Map<String, EngineSelection> = emptyMap(), val issues: Map<String, String> = emptyMap())

/** Per-model ownership; two downloads and an independent import checker survive navigation. */
internal class DesktopModelController(
    val library: DesktopModelLibrary, private val scope: CoroutineScope, private val error: (String) -> Unit,
    private val downloadModel: suspend (String, (ModelInstallProgress) -> Unit) -> EngineSelection = { id, activity -> library.download(id, activity = activity) },
    private val verifyModel: (suspend (String, Path) -> EngineSelection)? = null,
) {
    private val mutableState = MutableStateFlow(ModelLibraryView())
    val state: StateFlow<ModelLibraryView> = mutableState.asStateFlow()
    private val lock = Any()
    private val operations = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val downloads = Semaphore(2)
    private val imports = Semaphore(1)
    private var refreshJob: Job? = null
    private var closed = false

    fun refresh(config: PipelineConfig) = synchronized(lock) {
        if (closed || refreshJob?.isActive == true) return@synchronized
        val expectedGenerations = generations.toMap()
        mutableState.update { it.copy(refreshing = true) }
        refreshJob = scope.launch {
            try {
                library.refresh()
                PipelineStage.entries.mapNotNull(config::selection).forEach { route ->
                    if (route.modelPath.isNotBlank() && library.catalog.any { it.id == route.modelId } &&
                        library.installedSelection(route.modelId)?.modelPath != route.modelPath &&
                        route.modelId !in state.value.transfers) {
                        try {
                            val verified = verifyModel?.invoke(route.modelId, Path.of(route.modelPath)) ?: library.verify(route.modelId, Path.of(route.modelPath))
                            synchronized(lock) {
                                if (generations[route.modelId] == expectedGenerations[route.modelId] && route.modelId !in operations) {
                                    mutableState.update { it.copy(verifiedImports = it.verifiedImports + (route.modelId to verified), issues = it.issues - route.modelId) }
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val issue = e.message ?: e.toString()
                            synchronized(lock) {
                                if (generations[route.modelId] == expectedGenerations[route.modelId] && route.modelId !in operations) {
                                    mutableState.update { it.copy(verifiedImports = it.verifiedImports - route.modelId, issues = it.issues + (route.modelId to issue)) }
                                    error("${library.find(route.modelId).name}: $issue")
                                }
                            }
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: e.toString()) }
            finally { mutableState.update { it.copy(refreshing = false) } }
        }
    }

    fun download(id: String) = launch(id, importing = false) {
        downloadModel(id, progressReporter(id))
        // A completed explicit managed install supersedes any cached external pick
        // for this ID; activation still requires Use and other imports stay intact.
        mutableState.update { it.copy(verifiedImports = it.verifiedImports - id) }
    }

    private fun progressReporter(id: String): (ModelInstallProgress) -> Unit {
        // Disk/network callbacks can arrive thousands of times a second. Publish at
        // most ten times a second, plus phase changes and final progress.
        var last: ModelInstallProgress? = null
        var publishedAt = 0L
        return { progress ->
            val now = System.nanoTime()
            if (progress.phase != last?.phase || now - publishedAt >= 100_000_000L ||
                progress.completed == progress.total && progress.completed != last?.completed) {
                last = progress
                publishedAt = now
                mutableState.update { view -> view.copy(transfers = view.transfers[id]?.let { current ->
                    view.transfers + (id to current.copy(downloaded = progress.completed, total = progress.total,
                        phase = progress.phase, component = progress.component))
                } ?: view.transfers) }
            }
        }
    }

    fun verify(id: String, path: Path) = launch(id, importing = true) {
        val verified = verifyModel?.invoke(id, path) ?: library.verify(id, path, progressReporter(id))
        mutableState.update { it.copy(verifiedImports = it.verifiedImports + (id to verified)) }
    }

    /** Verification and activation remain separate; imports take precedence after an explicit pick. */
    fun verifiedSelection(id: String): EngineSelection? = state.value.verifiedImports[id] ?: library.installedSelection(id)

    fun cancel(id: String) = synchronized(lock) {
        val job = operations[id] ?: return@synchronized
        mutableState.update { view -> view.copy(transfers = view.transfers[id]?.let { view.transfers + (id to it.copy(cancelling = true)) } ?: view.transfers,
            notice = "${library.find(id).name} cancelled") }
        job.cancel()
    }

    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }

    suspend fun reconnectImportedSetup(config: PipelineConfig): PipelineConfig {
        checkSetupImportAvailable()
        // Setups contain IDs, not external paths. Resolve against verified selections
        // without forcing every unrelated in-flight download to finish first.
        return reconnectInstalledModels(config, ::verifiedSelection)
    }

    fun checkSetupImportAvailable() = synchronized(lock) { check(!closed) { "Model library is closing" } }

    suspend fun close() {
        val jobs = synchronized(lock) { closed = true; operations.values.toList() + listOfNotNull(refreshJob) }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    private fun launch(id: String, importing: Boolean, block: suspend () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        val name = library.find(id).name
        if (id in operations) {
            mutableState.update { it.copy(notice = "$name is already in Downloads") }
            return@synchronized
        }
        generations[id] = (generations[id] ?: 0L) + 1
        mutableState.update { it.copy(transfers = it.transfers + (id to ModelTransfer(id, queued = true, importing = importing)),
            notice = if (importing) "Checking $name import" else "$name added to Downloads", issues = it.issues - id) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                (if (importing) imports else downloads).withPermit {
                    mutableState.update { view -> view.copy(transfers = view.transfers + (id to requireNotNull(view.transfers[id]).copy(queued = false))) }
                    block()
                    mutableState.update { it.copy(notice = "$name ${if (importing) "import verified" else "installed"}. Choose Use to activate it.", issues = it.issues - id) }
                }
            } catch(e: CancellationException) {
                mutableState.update { it.copy(notice = "$name cancelled") }
                throw e
            } catch(e: Exception) {
                val issue = e.message ?: e.toString()
                mutableState.update { it.copy(issues = it.issues + (id to issue), notice = "$name failed: $issue") }
                error("$name: $issue")
            }
        }
        operations[id] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                operations.remove(id)
                mutableState.update { it.copy(transfers = it.transfers - id) }
            }
        }
        job.start()
    }
}
