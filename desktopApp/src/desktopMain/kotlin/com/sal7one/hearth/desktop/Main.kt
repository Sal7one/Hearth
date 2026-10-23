package com.sal7one.hearth.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.core.HearthPipeline
import com.sal7one.hearth.desktop.data.DesktopCredentialStore
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.data.DesktopSettingsStore
import com.sal7one.hearth.desktop.runtime.DesktopEngines
import com.sal7one.hearth.desktop.ui.HearthDesktopRoot
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path

private class DesktopServices(
    val settings: DesktopSettingsStore, val credentials: DesktopCredentialStore,
    val engines: DesktopEngines, val models: DesktopModelController, val controller: DesktopWorkspaceController,
    val reading: DesktopReadingController,
    val readingService: DesktopReadingService,
) {
    suspend fun close() {
        withContext(NonCancellable) {
            var failure: Exception? = null
            suspend fun release(block: suspend () -> Unit) {
                try { block() }
                catch (e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
            }
            release { controller.close() }
            release { readingService.close() }
            release { reading.close() }
            release { models.close() }
            release { withContext(Dispatchers.IO) { engines.close() } }
            release { settings.closeAndJoin() }
            failure?.let { throw it }
        }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--overlay-preview") {
        require(args.size == 2) { "Usage: Hearth --overlay-preview <isolated-preview-directory>" }
        launchOverlayPreview(Path.of(args[1]))
        return
    }
    if (args.firstOrNull() == "--gpu-check") {
        require(args.size == 6) { "Usage: Hearth --gpu-check <metal|cuda> <whisper-model> <EuroLLM-model> <wav> <receipt-json>" }
        com.sal7one.hearth.desktop.runtime.DesktopGpuValidation.run(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]))
        return
    }
    if (args.firstOrNull() == "--self-check") {
        require(args.size == 2) { "Usage: Hearth --self-check <report-path>" }
        DesktopSelfCheck.run(Path.of(args[1]))
        return
    }
    launchDesktop()
}

private fun launchDesktop() = application {
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf<DesktopServices?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(retry) {
        startupError = null
        var settings: DesktopSettingsStore? = null
        var engines: DesktopEngines? = null
        var candidate: DesktopServices? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val directory = desktopDataDirectory()
                val store = DesktopSettingsStore(directory)
                settings = store
                store.load()
                val credentials = DesktopCredentialStore(directory)
                val models = DesktopModelLibrary(directory)
                val runtime = DesktopEngines(credentials, directory)
                engines = runtime
                val pipeline = HearthPipeline(runtime, scope)
                val capabilities = pipeline.capabilities()
                val resolved = store.config.value.withDetectedCompute(capabilities)
                if (resolved != store.config.value) store.save(resolved)
                lateinit var modelController: DesktopModelController
                val readingService = DesktopReadingService(pipeline, scope, selection = {
                    val id = selectedReadingModel(store.ui.value)
                    modelController.verifiedSelection(id)?.modelPath?.let { Path.of(it) to id }
                })
                val controller = DesktopWorkspaceController(pipeline, store.config, directory, scope,
                    persist = { store.save(it.withDetectedCompute(capabilities)) }, putSecret = { id, secret -> credentials.put(id, secret) },
                    removeSecret = { credentials.remove(it) }, exportSetup = { store.exportSetup(it) },
                    importSetup = {
                        modelController.checkSetupImportAvailable()
                        store.importSetup(it)
                        store.save(modelController.reconnectImportedSetup(store.config.value).withDetectedCompute(capabilities))
                    }, persistUi = { store.saveUi(it) }, capabilities = capabilities,
                    readingService = readingService,
                    integrationOrigins = { setOfNotNull(store.ui.value["browser-extension-origin"]?.takeIf(String::isNotBlank)) })
                modelController = DesktopModelController(models, scope, controller::showError, settings = store)
                val reading = DesktopReadingController(pipeline, store.config, scope)
                DesktopServices(store, credentials, runtime, modelController, controller, reading, readingService)
                    .also { candidate = it }
            }
            services = loaded
            loaded.models.refresh(loaded.settings.config.value)
            loaded.settings.ui.value["reading-model-path"]?.takeIf(String::isNotBlank)?.let {
                loaded.models.verify(selectedReadingModel(loaded.settings.ui.value), Path.of(it))
            }
        } catch(e: Exception) {
            withContext(NonCancellable) {
                try {
                    candidate?.close() ?: run {
                        try { withContext(Dispatchers.IO) { engines?.close() } }
                        finally { settings?.closeAndJoin() }
                    }
                } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            startupError = e.message ?: e.toString()
        }
    }
    Window(title = "Hearth", icon = painterResource("hearth.png"),
        state = rememberWindowState(width = 1400.dp, height = 960.dp), onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        services?.close()
                        exitApplication()
                    } catch(e: Exception) {
                        services?.controller?.showError("Cannot finish shutdown: ${e.message ?: e}")
                        closing = false
                    }
                }
            }
        }) {
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(1120, 780) }
        val loaded = services
        if (loaded != null) {
            val prefs by loaded.settings.ui.collectAsState()
            val settingsError by loaded.settings.error.collectAsState()
            LaunchedEffect(settingsError) { settingsError?.let(loaded.controller::showError) }
            HearthDesktopRoot(
                controller = loaded.controller,
                models = loaded.models,
                reading = loaded.reading,
                initialUi = prefs,
                saveUi = { loaded.controller.saveUi(it) },
                closing = closing,
                rememberedEngine = { loaded.settings.engineSetups.value[it] },
                onShowWindow = { window.isVisible = true; window.toFront(); window.requestFocus() },
                onHideWindow = { window.isVisible = false },
            )
        } else {
            StartupScreen(startupError) { retry++ }
        }
    }
}

@Composable
private fun StartupScreen(error: String?, onRetry: () -> Unit) {
    HearthTheme {
        Column(
            Modifier.fillMaxSize().background(HearthTheme.colors.bg).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Hearth", style = HearthType.h1, color = HearthTheme.colors.text)
            if (error == null) {
                CircularProgressIndicator(Modifier.size(22.dp), color = HearthTheme.colors.accent)
                Text("Loading settings and checking engines…", style = HearthType.small, color = HearthTheme.colors.muted)
            } else {
                Text(error, style = HearthType.small, color = HearthTheme.colors.err)
                HearthButton("Retry loading settings", onRetry, tone = ButtonTone.Primary)
            }
        }
    }
}
