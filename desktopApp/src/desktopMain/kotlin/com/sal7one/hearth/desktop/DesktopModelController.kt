package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.data.ModelInstallPhase
import com.sal7one.hearth.desktop.data.ModelInstallProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path

internal data class ModelTransfer(val id: String, val downloaded: Long = 0, val total: Long = 0,
    val phase: ModelInstallPhase = ModelInstallPhase.DOWNLOADING, val queued: Boolean = false,
    val importing: Boolean = false, val cancelling: Boolean = false, val component: String = "", val paused: Boolean = false) {
    val checking: Boolean get() = phase == ModelInstallPhase.CHECKING || phase == ModelInstallPhase.VERIFYING
    val label: String get() = when {
        paused -> "Paused"
        cancelling -> "Pausing"
        queued -> "Queued"
        importing -> "Checking import"
        phase == ModelInstallPhase.CHECKING -> "Checking existing files"
        phase == ModelInstallPhase.EXTRACTING -> "Unpacking"
        phase == ModelInstallPhase.VERIFYING -> "Verifying"
        else -> "Downloading"
    }
}
internal data class ModelCopyKey(val id:String,val path:String)
internal fun modelCopyKey(id:String,path:String)=ModelCopyKey(id,Path.of(path).toAbsolutePath().normalize().toString())

internal data class ModelLibraryView(val transfers: Map<String, ModelTransfer> = emptyMap(), val refreshing: Boolean = false,
    val notice: String? = null, val verifiedImports: Map<String, EngineSelection> = emptyMap(), val issues: Map<String, String> = emptyMap(),
    val options: DesktopDownloadOptions = DesktopDownloadOptions(), val measurements: List<DesktopTransferMeasurement> = emptyList(),
    val verifyingSelections: Map<String,String> = emptyMap(),
    val verifiedCopies: Map<ModelCopyKey,EngineSelection> = emptyMap(),
    val checkingCopies: Set<ModelCopyKey> = emptySet(), val copyIssues: Map<ModelCopyKey,String> = emptyMap())

/** Durable per-model ownership. Transfers, integrity checks and imports have independent lanes. */
internal class DesktopModelController(
    val library: DesktopModelLibrary, private val scope: CoroutineScope, private val error: (String) -> Unit,
    private val settings: DesktopSettingsStore? = null,
    private val downloadModel: (suspend (String, (ModelInstallProgress) -> Unit) -> EngineSelection)? = null,
    private val verifyModel: (suspend (String, Path) -> EngineSelection)? = null,
) {
    private val journal = library.downloads
    private val mutableState = MutableStateFlow(ModelLibraryView(
        transfers = journal.state.value.queue.filter { it.phase == DesktopQueuePhase.PAUSED }.associate { it.id to
            ModelTransfer(it.id, it.bytes, it.total, paused = true) },
        issues = journal.state.value.queue.mapNotNull { q -> q.issue?.let { q.id to it } }.toMap(),
        options = journal.state.value.options, measurements = journal.state.value.measurements, notice = journal.state.value.issue))
    val state: StateFlow<ModelLibraryView> = mutableState.asStateFlow()
    private val lock = Any()
    private val operations = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val copyGenerations = mutableMapOf<ModelCopyKey,Long>()
    private val imports = Semaphore(1)
    private val injectedDownloads = Semaphore(2)
    private var refreshJob: Job? = null
    private var refreshConfig: PipelineConfig? = null
    private var queuedRefresh: PipelineConfig? = null
    private var completedAuditCopies: Set<ModelCopyKey> = emptySet()
    private var closed = false
    init {
        settings?.downloadOptions?.value?.let { options ->
            library.configureDownloads(options)
            mutableState.update { it.copy(options = options) }
        }
    }

    fun refresh(config: PipelineConfig): Unit = synchronized(lock) {
        if (closed) return@synchronized
        fun routes(value: PipelineConfig)=PipelineStage.entries.mapNotNull(value::selection).filter { route ->
            route.modelPath.isNotBlank() && library.catalog.any {it.id==route.modelId && it.engineId==route.engineId}
        }.associateBy {it.modelId}
        val configured=routes(config)
        if(refreshJob?.isActive==true) {
            if(configured!=refreshConfig?.let(::routes)) {
                queuedRefresh=config
                mutableState.update {it.copy(verifyingSelections=it.verifyingSelections+configured.mapValues {route ->route.value.modelPath})}
            } else if(queuedRefresh!=null) {
                queuedRefresh=null
                mutableState.update {it.copy(verifyingSelections=configured.filter {(_,route) ->modelCopyKey(route.modelId,route.modelPath) !in completedAuditCopies}.mapValues {route ->route.value.modelPath})}
            }
            return@synchronized
        }
        refreshConfig=config
        completedAuditCopies=emptySet()
        val expectedGenerations = generations.toMap()
        val expectedCopies=copyGenerations.toMap()
        mutableState.update { it.copy(refreshing = true, verifyingSelections = configured.mapValues {it.value.modelPath}) }
        refreshJob = scope.launch {
            suspend fun checkRoute(route: EngineSelection) {
                try {
                    val key=modelCopyKey(route.modelId,route.modelPath)
                    val managed=library.installedSelection(route.modelId)
                    val owned=synchronized(lock) {route.modelId in operations}
                    if(managed?.modelPath==route.modelPath && !owned) {
                        synchronized(lock) {
                            if(copyGenerations[key]==expectedCopies[key]) mutableState.update {it.copy(verifiedCopies=boundedProofs(it,key,managed),copyIssues=it.copyIssues-key)}
                            if(generations[route.modelId]==expectedGenerations[route.modelId])
                                mutableState.update {it.copy(verifiedImports=it.verifiedImports-route.modelId,issues=it.issues-route.modelId)}
                        }
                    } else {
                        try {
                            val verified = verifyModel?.invoke(route.modelId, Path.of(route.modelPath)) ?: library.verify(route.modelId, Path.of(route.modelPath))
                            synchronized(lock) {
                                if(copyGenerations[key]==expectedCopies[key]) mutableState.update {it.copy(verifiedCopies=boundedProofs(it,key,verified),copyIssues=it.copyIssues-key)}
                                if (generations[route.modelId] == expectedGenerations[route.modelId] && route.modelId !in operations &&
                                    (queuedRefresh==null || routes(requireNotNull(queuedRefresh))[route.modelId]?.modelPath==route.modelPath)) {
                                    mutableState.update { it.copy(verifiedImports = it.verifiedImports + (route.modelId to verified), issues = it.issues - route.modelId) }
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val issue = e.message ?: e.toString()
                            synchronized(lock) {
                                if(copyGenerations[key]==expectedCopies[key]) mutableState.update {it.copy(verifiedCopies=it.verifiedCopies-key,copyIssues=it.copyIssues+(key to issue))}
                                if (generations[route.modelId] == expectedGenerations[route.modelId] && route.modelId !in operations &&
                                    (queuedRefresh==null || routes(requireNotNull(queuedRefresh))[route.modelId]?.modelPath==route.modelPath)) {
                                    mutableState.update { it.copy(verifiedImports = it.verifiedImports - route.modelId, issues = it.issues + (route.modelId to issue)) }
                                    error("${library.find(route.modelId).name}: $issue")
                                }
                            }
                        }
                    }
                } finally {synchronized(lock) {
                    completedAuditCopies=completedAuditCopies+modelCopyKey(route.modelId,route.modelPath)
                    mutableState.update {view ->if(view.verifyingSelections[route.modelId]==route.modelPath)view.copy(verifyingSelections=view.verifyingSelections-route.modelId) else view}
                }}
            }
            try {
                library.refresh(configured.keys) { id -> configured[id]?.let {checkRoute(it)} }
                // Unselected imports are still restored, but never delay the saved
                // live-caption setup behind an unrelated model's hash.
                journal.state.value.imports.filterKeys {it !in configured}.forEach { (id,path) ->
                    library.catalog.firstOrNull {it.id==id}?.let {checkRoute(EngineSelection(it.engineId,id,path))}
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: e.toString()) }
            finally { synchronized(lock) {
                val next=queuedRefresh.takeUnless {closed};queuedRefresh=null;refreshJob=null
                mutableState.update {it.copy(refreshing=next!=null,verifyingSelections=next?.let(::routes)?.mapValues {route ->route.value.modelPath}.orEmpty())}
                if(next!=null)refresh(next)
            } }
        }
    }

    private fun boundedProofs(view:ModelLibraryView,key:ModelCopyKey,route:EngineSelection):Map<ModelCopyKey,EngineSelection> {
        val updated=(view.verifiedCopies-key)+(key to route)
        val limit=maxOf(256,library.catalog.size*4)
        if(updated.size<=limit)return updated
        val active=listOfNotNull(refreshConfig,queuedRefresh).flatMap {listOfNotNull(it.speech,it.translation,it.voice)}
            .filter {it.modelPath.isNotBlank()}.mapNotNull {runCatching {modelCopyKey(it.modelId,it.modelPath)}.getOrNull()}.toSet()+view.checkingCopies
        val retained=updated.toMutableMap()
        for(candidate in updated.keys) {
            if(retained.size<=limit)break
            if(candidate !in active && candidate!=key)retained.remove(candidate)
        }
        return retained
    }

    fun download(id: String) = launch(id, importing = false) { install(id) }
    fun restart(id: String) = launch(id, importing = false) { library.discardPartial(id); install(id) }
    private suspend fun install(id: String) {
        if (downloadModel != null) injectedDownloads.withPermit {
            mutableState.update { view -> view.copy(transfers = view.transfers + (id to requireNotNull(view.transfers[id]).copy(queued = false))) }
            downloadModel.invoke(id, progressReporter(id))
        }
        else library.download(id, activity = progressReporter(id), options = state.value.options, waiting = { waiting ->
            mutableState.update { view -> view.copy(transfers = view.transfers[id]?.let { current ->
                view.transfers + (id to current.copy(queued = waiting)) } ?: view.transfers) }
        })
        journal.forgetImport(id)
        // A completed explicit managed install supersedes any cached external pick
        // for this ID; activation still requires Use and other imports stay intact.
        mutableState.update { it.copy(verifiedImports = it.verifiedImports - id) }
    }

    private fun progressReporter(id: String): (ModelInstallProgress) -> Unit {
        // Disk/network callbacks can arrive thousands of times a second. Publish at
        // most ten times a second, plus phase changes and final progress.
        var last: ModelInstallProgress? = null
        var publishedAt = 0L
        var savedAt = 0L
        return { progress ->
            val now = System.nanoTime()
            if (progress.phase != last?.phase || now - publishedAt >= 100_000_000L ||
                progress.completed == progress.total && progress.completed != last?.completed) {
                val changedPhase = progress.phase != last?.phase
                last = progress
                publishedAt = now
                if (now - savedAt >= 1_000_000_000L || changedPhase) {
                    savedAt = now
                    val active = state.value.transfers[id]
                    if (active != null && !active.importing && !active.paused) {
                        journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.ACTIVE, progress.completed, progress.total))
                    }
                }
                mutableState.update { view -> view.copy(transfers = view.transfers[id]?.let { current ->
                    view.transfers + (id to current.copy(downloaded = progress.completed, total = progress.total,
                        phase = progress.phase, component = progress.component))
                } ?: view.transfers) }
            }
        }
    }

    fun verify(id: String, path: Path) = launch(id, importing = true, selectedPath=path) {
        val verified = verifyModel?.invoke(id, path) ?: library.verify(id, path, progressReporter(id))
        journal.imported(id, path)
        journal.remove(id)
        val key=modelCopyKey(id,path.toString())
        mutableState.update { it.copy(verifiedImports = it.verifiedImports + (id to verified),verifiedCopies=boundedProofs(it,key,verified),copyIssues=it.copyIssues-key) }
    }

    /** Verification and activation remain separate; imports take precedence after an explicit pick. */
    fun verifiedSelection(id: String): EngineSelection? {
        val view=state.value
        val selected=view.verifiedImports[id] ?: library.installedSelection(id) ?: return null
        val key=runCatching {modelCopyKey(id,selected.modelPath)}.getOrNull() ?: return null
        return selected.takeUnless {view.verifyingSelections[id]==it.modelPath || key in view.checkingCopies || key in view.copyIssues ||
            synchronized(lock) {key in copyGenerations} && key !in view.verifiedCopies}
    }

    /** Readiness follows the saved copy, independently of another copy's library Use choice. */
    fun selectedStatus(id:String,path:String): DesktopModelStatus? {
        if(path.isBlank())return null
        val key=runCatching {modelCopyKey(id,path)}.getOrNull() ?: return null
        val view=state.value
        if(key in view.checkingCopies || view.verifyingSelections[id]?.let {runCatching {modelCopyKey(id,it)==key}.getOrDefault(false)}==true)
            return DesktopModelStatus(false,Path.of(path),"Checking the saved model files. Your selection is preserved.",checking=true)
        view.copyIssues[key]?.let {return DesktopModelStatus(false,Path.of(path),it)}
        val proof=view.verifiedCopies[key]
        if(proof!=null)return DesktopModelStatus(true,Path.of(proof.modelPath))
        if(synchronized(lock) {key in copyGenerations})return DesktopModelStatus(false,Path.of(path),
            if(view.transfers[id]?.paused==true)"Saved model preparation is paused. Resume it in Downloads or verify this copy again."
            else "The saved copy's verification was interrupted. Verify this copy again.")
        return null
    }

    /** Pause retains authenticated staging and the durable entry. Closing the app has the same semantics. */
    fun pause(id: String) = synchronized(lock) {
        val job = operations[id] ?: return@synchronized
        val transfer = state.value.transfers[id] ?: return@synchronized
        if (transfer.importing) {
            mutableState.update { it.copy(transfers = it.transfers - id, notice = "Import check cancelled") }
        } else {
            journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.PAUSED, transfer.downloaded, transfer.total))
            mutableState.update { it.copy(transfers = it.transfers + (id to transfer.copy(paused = true, queued = false, cancelling = true)),
                notice = "${library.find(id).name} paused; downloaded bytes are retained") }
        }
        job.cancel()
    }
    fun cancel(id: String) = synchronized(lock) {
        val job = operations[id] ?: return@synchronized
        journal.remove(id)
        mutableState.update { view -> view.copy(transfers = view.transfers[id]?.let {
            view.transfers + (id to it.copy(cancelling = true, paused = false)) } ?: view.transfers, notice = "${library.find(id).name} cancelled") }
        job.cancel()
    }
    fun pauseAll() { state.value.transfers.values.filter { !it.importing && !it.paused }.map { it.id }.forEach(::pause) }
    fun resumeAll() { state.value.transfers.values.filter { it.paused }.map { it.id }.forEach(::download) }
    fun downloadAll(ids: Collection<String>) { ids.distinct().forEach(::download) }
    fun configure(options: DesktopDownloadOptions) {
        scope.launch {
            try {
                settings?.saveDownloadOptions(options)
                withContext(Dispatchers.IO) { library.configureDownloads(options) }
                mutableState.update { it.copy(options = options, notice = "Download options saved. New transfers use these settings.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("Cannot save download options: ${e.message}") }
        }
    }
    fun exportMeasurements(): Path = library.directory.resolve("download-benchmarks.csv").also(journal::exportMeasurements)
    fun forget(id: String) = synchronized(lock) {
        if (id in operations) return@synchronized
        journal.remove(id)
        mutableState.update { it.copy(transfers = it.transfers - id, issues = it.issues - id) }
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
        val jobs = synchronized(lock) {
            closed = true
            state.value.transfers.values.filter { !it.importing }.forEach { transfer ->
                journal.queue(DesktopQueueEntry(transfer.id, DesktopQueuePhase.PAUSED, transfer.downloaded, transfer.total))
            }
            operations.values.toList() + listOfNotNull(refreshJob)
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        mutableState.update { it.copy(transfers = emptyMap()) }
    }

    private fun launch(id: String, importing: Boolean, selectedPath:Path?=null, block: suspend () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        val name = library.find(id).name
        if (id in operations) {
            mutableState.update { it.copy(notice = "$name is already in Downloads") }
            return@synchronized
        }
        generations[id] = (generations[id] ?: 0L) + 1
        val card=library.find(id)
        val managedRoot=library.directory.resolve("models").resolve(id)
        val path=selectedPath ?: if(card.artifacts.size==1 && card.archive==null && card.rangePackage==null)managedRoot.resolve(card.artifacts.single().filename) else managedRoot
        val copyKey=modelCopyKey(id,path.toString())
        copyGenerations[copyKey]=(copyGenerations[copyKey] ?: 0L)+1
        val previous = state.value.transfers[id]
        if (!importing) journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.WAITING, previous?.downloaded ?: 0, library.find(id).bytes))
        mutableState.update { it.copy(transfers = it.transfers + (id to ModelTransfer(id, downloaded = previous?.downloaded ?: 0, total = library.find(id).bytes, queued = true, importing = importing)),
            notice = if (importing) "Checking $name import" else "$name added to Downloads", issues = it.issues - id,
            checkingCopies=it.checkingCopies+copyKey,verifiedCopies=it.verifiedCopies-copyKey,copyIssues=it.copyIssues-copyKey) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                suspend fun perform() {
                    if (importing || downloadModel == null) mutableState.update { view -> view.copy(transfers = view.transfers + (id to requireNotNull(view.transfers[id]).copy(queued = false))) }
                    block()
                    if(!importing)library.installedSelection(id)?.let {verified ->mutableState.update {it.copy(verifiedCopies=boundedProofs(it,copyKey,verified),copyIssues=it.copyIssues-copyKey)}}
                    if (!importing) journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.COMPLETE, library.find(id).bytes, library.find(id).bytes))
                    mutableState.update { it.copy(measurements = journal.state.value.measurements, notice = "$name ${if (importing) "import verified" else "installed"}. Choose Use to activate it.", issues = it.issues - id) }
                }
                if (importing) imports.withPermit { perform() } else perform()
            } catch(e: CancellationException) {
                if (!importing && journal.state.value.queue.any { it.id == id && it.phase == DesktopQueuePhase.PAUSED }) {
                    val current = state.value.transfers[id]
                    if (current != null) {
                        journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.PAUSED, current.downloaded, current.total))
                        mutableState.update { it.copy(transfers = it.transfers + (id to current.copy(paused = true, queued = false, cancelling = false)), notice = "$name paused") }
                    }
                }
                throw e
            } catch(e: Exception) {
                val issue = e.message ?: e.toString()
                if (!importing) journal.queue(DesktopQueueEntry(id, DesktopQueuePhase.FAILED, issue = issue))
                mutableState.update { it.copy(issues = it.issues + (id to issue), notice = "$name failed: $issue",verifiedCopies=it.verifiedCopies-copyKey,
                    copyIssues=it.copyIssues+(copyKey to issue),verifiedImports=it.verifiedImports.filterValues {route ->runCatching {modelCopyKey(id,route.modelPath)!=copyKey}.getOrDefault(true)}) }
                error("$name: $issue")
            }
        }
        operations[id] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                operations.remove(id)
                mutableState.update { it.copy(checkingCopies=it.checkingCopies-copyKey,transfers = if (it.transfers[id]?.paused == true) it.transfers + (id to requireNotNull(it.transfers[id]).copy(cancelling = false)) else it.transfers - id) }
            }
        }
        job.start()
    }
}
