package com.sal7one.transiber.models

import android.content.Context
import com.sal7one.transiber.preferences.ModelSourceBindings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A short source-status refresh must outlive a downloader service/settings composition.
 * It never changes the successful installation into a failed transfer.
 */
internal object ModelInstallRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableFailures = MutableStateFlow<String?>(null)
    val failures = mutableFailures.asStateFlow()
    fun schedule(context: Context, onError: (String) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try { ModelSourceBindings.recheck(app); mutableFailures.value = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = "Installed successfully, but model-source status could not refresh: ${e.message ?: e}"
                mutableFailures.value = message
                try { onError(message) }
                catch (diagnostic: Exception) { mutableFailures.value = "$message\nCannot save diagnostic: ${diagnostic.message ?: diagnostic}" }
            }
        }
    }
}
