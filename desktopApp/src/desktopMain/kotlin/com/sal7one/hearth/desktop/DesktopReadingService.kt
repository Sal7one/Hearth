package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineService
import com.sal7one.hearth.desktop.runtime.DesktopMangaOcr
import com.sal7one.hearth.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.UUID

/** One resident reader for API jobs. UI and service inference also share DesktopReadingAdmission. */
internal class DesktopReadingService(
    pipeline: PipelineService,
    parent: CoroutineScope,
    private val selection: () -> Pair<Path, String>?,
    private val runtimeIssue: () -> String? = DesktopMangaOcr::runtimeIssue,
    readerFactory: ((StateFlow<PipelineConfig>, CoroutineScope) -> DesktopReadingController)? = null,
) : IntegrationReadingService {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val config = MutableStateFlow(PipelineConfig())
    private val reader = readerFactory?.invoke(config, scope) ?: DesktopReadingController(pipeline, config, scope)
    private val requests = Mutex()

    override fun capabilities(): IntegrationReadingCapabilities {
        val problem = when {
            !owner.isActive -> "Image reading service is closed"
            selection() == null -> "Install or verify a Japanese reading model in Reading first"
            else -> runtimeIssue()
        }
        return IntegrationReadingCapabilities(problem == null, setOf("ja"), problem)
    }

    override fun start(request: IntegrationReadingRequest): IntegrationReadingSession {
        check(owner.isActive) { "Image reading service is closed" }
        require(request.config.sourceLanguage == "ja") { "Image reading currently supports Japanese source text" }
        val selected = selection() ?: error("Install or verify a Japanese reading model in Reading first")
        runtimeIssue()?.let { error(it) }
        val id = UUID.randomUUID().toString()
        val state = MutableStateFlow(IntegrationReadingSnapshot(id, IntegrationReadingStatus.RUNNING,
            request.image.width, request.image.height, "Waiting for the reading pipeline…"))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                requests.withLock {
                    ensureActive()
                    config.value = request.config
                    var operation: Job? = null
                    var observation: Job? = null
                    try {
                        operation = reader.readFrame(request.image, selected.first, request.translate, selected.second)
                            ?: error("The reading controller is closed")
                        observation = launch {
                            reader.state.collect { current ->
                                // A job waiting for native admission still exposes the resident
                                // reader's previous image. Never publish another request's pixels.
                                if (current.image === request.image)
                                    state.value = current.snapshot(id, request.image.width, request.image.height)
                            }
                        }
                        operation.join()
                        ensureActive()
                        observation.cancelAndJoin()
                        val result = reader.state.value
                        check(result.image === request.image) { "The reading request lost ownership of its image" }
                        state.value = result.snapshot(id, request.image.width, request.image.height).copy(
                            status = if (result.error == null) IntegrationReadingStatus.COMPLETED else IntegrationReadingStatus.FAILED)
                    } finally {
                        // readFrame owns a native job outside this request's coroutine. Join it before
                        // another request can enter; cancellation must never merely discard its result.
                        withContext(NonCancellable) {
                            if (operation?.isActive == true) { reader.cancel(); operation.join() }
                            observation?.cancelAndJoin()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                state.update { it.copy(status = IntegrationReadingStatus.CANCELLED, phase = "Cancelled") }
                throw cancelled
            } catch (failure: Exception) {
                state.update { it.copy(status = IntegrationReadingStatus.FAILED, phase = "Reading failed",
                    error = failure.message ?: failure.toString()) }
            }
        }
        job.start()
        return object : IntegrationReadingSession {
            override val id = id
            override val state: StateFlow<IntegrationReadingSnapshot> = state.asStateFlow()
            override suspend fun cancel() {
                job.cancelAndJoin()
                state.update { if (it.terminal) it else it.copy(status = IntegrationReadingStatus.CANCELLED, phase = "Cancelled") }
            }
        }
    }

    suspend fun close() {
        owner.cancelAndJoin()
        reader.close()
    }
}

private fun DesktopReadingState.snapshot(id: String, width: Int, height: Int): IntegrationReadingSnapshot {
    val transcripts = text.associateBy { it.region.bounds }
    return IntegrationReadingSnapshot(id, IntegrationReadingStatus.RUNNING, width, height, phase,
        regions.mapIndexed { index, region ->
            IntegrationReadingRegion(index.toString(), region.bounds, transcripts[region.bounds]?.text, translations[region.bounds])
        }, error)
}
