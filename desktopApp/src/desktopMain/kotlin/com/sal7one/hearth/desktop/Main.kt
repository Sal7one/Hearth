package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.*
import java.nio.file.Path

private class DesktopServices(
    val settings: DesktopSettingsStore, val credentials: DesktopCredentialStore,
    val engines: DesktopEngines, val models: DesktopModelController, val controller: DesktopWorkspaceController,
) {
    suspend fun close() {
        withContext(NonCancellable) {
            var failure: Exception? = null
            suspend fun release(block: suspend () -> Unit) {
                try { block() }
                catch (e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) }
            }
            release { models.close() }
            release { controller.close() }
            release { withContext(Dispatchers.IO) { engines.close() } }
            release { settings.closeAndJoin() }
            failure?.let { throw it }
        }
    }
}

fun main(args: Array<String>) {
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
                lateinit var modelController: DesktopModelController
                val controller = DesktopWorkspaceController(pipeline, store.config, directory, scope,
                    persist = { store.save(it) }, putSecret = { id, secret -> credentials.put(id, secret) },
                    removeSecret = { credentials.remove(it) }, exportSetup = { store.exportSetup(it) },
                    importSetup = {
                        modelController.checkSetupImportAvailable()
                        store.importSetup(it)
                        store.save(modelController.reconnectImportedSetup(store.config.value))
                    }, persistUi = { store.saveUi(it) }, capabilities = capabilities)
                modelController = DesktopModelController(models, scope, controller::showError)
                DesktopServices(store, credentials, runtime, modelController, controller)
                    .also { candidate = it }
            }
            services = loaded
            loaded.models.refresh(loaded.settings.config.value)
        } catch(e: Exception) {
            withContext(NonCancellable) {
                try {
                    candidate?.close() ?: run {
                        try { withContext(Dispatchers.IO) { engines?.close() } }
                        finally { settings?.closeAndJoin() }
                    }
                } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
            if (e is CancellationException) throw e
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
            HearthDesktopApp(loaded.controller, window, prefs, saveUi = { loaded.controller.saveUi(it) },
                credentialStorageDescription = loaded.credentials.storageDescription, models = loaded.models, closing = closing)
        } else HearthDesktopTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    Text("Hearth", style = MaterialTheme.typography.headlineLarge)
                    if (startupError == null) { CircularProgressIndicator(); Text("Loading settings and checking engines…") }
                    else {
                        Text(startupError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { retry++ }) { Text("Retry loading settings") }
                    }
                }
            }
        }
    }
}
