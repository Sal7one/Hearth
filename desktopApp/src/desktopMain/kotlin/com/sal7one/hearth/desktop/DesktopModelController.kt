package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path

internal data class ModelTransfer(val id: String, val downloaded: Long = 0, val total: Long = 0,
    val checking: Boolean = false, val cancelling: Boolean = false)
internal data class ModelLibraryView(val transfer: ModelTransfer? = null, val refreshing: Boolean = false,
    val verifiedImports: Map<String, EngineSelection> = emptyMap(), val issues: Map<String, String> = emptyMap())

/** Application-scoped work survives navigation; shutdown cancels and joins its real transfer. */
internal class DesktopModelController(
    val library: DesktopModelLibrary, private val scope: CoroutineScope, private val error: (String) -> Unit,
    private val verifyModel: suspend (String, Path) -> EngineSelection = library::verify,
) {
    private val mutableState = MutableStateFlow(ModelLibraryView())
    val state: StateFlow<ModelLibraryView> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var closed = false

    fun refresh(config: PipelineConfig) {
        if (closed || operation?.isActive == true) return
        mutableState.update { it.copy(refreshing = true) }
        operation = scope.launch {
            try {
                library.refresh()
                val imported = mutableMapOf<String, EngineSelection>()
                val issues = mutableMapOf<String, String>()
                PipelineStage.entries.mapNotNull(config::selection).forEach { route ->
                    if (route.modelPath.isNotBlank() && library.catalog.any { it.id == route.modelId } &&
                        library.installedSelection(route.modelId)?.modelPath != route.modelPath) {
                        try { imported[route.modelId] = verifyModel(route.modelId, Path.of(route.modelPath)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { issues[route.modelId] = e.message ?: e.toString() }
                    }
                }
                mutableState.update { it.copy(verifiedImports = imported, issues = issues) }
                if (issues.isNotEmpty()) error(issues.entries.joinToString("\n") { (id, issue) -> "${library.find(id).name}: $issue" })
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: e.toString()) }
            finally { mutableState.update { it.copy(refreshing = false) } }
        }
    }

    fun download(id: String) = launch(id) {
        library.download(id) { done, total ->
            mutableState.update { previous -> previous.copy(transfer = previous.transfer?.copy(
                downloaded = done, total = total, checking = done >= total)) }
        }
    }

    fun verify(id: String, path: Path) = launch(id, checking = true) {
        val verified = verifyModel(id, path)
        mutableState.update { it.copy(verifiedImports = it.verifiedImports + (id to verified)) }
    }

    fun cancel() {
        mutableState.update { it.copy(transfer = it.transfer?.copy(cancelling = true)) }
        operation?.cancel()
    }

    suspend fun reconnectImportedSetup(config: PipelineConfig): PipelineConfig {
        checkSetupImportAvailable()
        library.refresh()
        mutableState.update { it.copy(verifiedImports = emptyMap(), issues = emptyMap()) }
        return reconnectInstalledModels(config, library::installedSelection)
    }

    fun checkSetupImportAvailable() {
        check(!closed) { "Model library is closing" }
        check(operation?.isActive != true) { "Wait for the current model check or transfer before importing a setup" }
    }

    suspend fun close() { closed = true; operation?.cancelAndJoin() }

    private fun launch(id: String, checking: Boolean = false, block: suspend () -> Unit) {
        if (closed) return
        if (operation?.isActive == true) { error("Wait for the current model check or transfer to finish"); return }
        mutableState.update { it.copy(transfer = ModelTransfer(id, checking = checking), issues = it.issues - id) }
        operation = scope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val issue = e.message ?: e.toString()
                mutableState.update { it.copy(issues = it.issues + (id to issue)) }
                error(issue)
            }
            finally { mutableState.update { it.copy(transfer = null) } }
        }
    }
}
