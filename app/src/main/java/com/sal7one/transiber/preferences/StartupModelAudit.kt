package com.sal7one.transiber.preferences

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A failed model audit is repairable and never prevents the rest of the app opening. */
data class StartupAuditResult(val preferencesError: String? = null, val modelError: String? = null) {
    val failed get() = preferencesError != null || modelError != null
}
internal class StartupAuditJob(private val scope: CoroutineScope,
    private val initialize: suspend () -> Unit, private val verify: suspend () -> Unit) {
    private var pending: Deferred<StartupAuditResult>? = null
    private val mutableResult = MutableStateFlow<StartupAuditResult?>(null)
    val result = mutableResult.asStateFlow()
    @Synchronized fun start(retry: Boolean = false): Deferred<StartupAuditResult> {
        val existing = pending
        if (existing != null && !(retry && existing.isCompleted && mutableResult.value?.failed == true)) return existing
        return scope.async {
            val preferencesError = try { initialize(); null }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { "${error.javaClass.simpleName}: ${error.message}" }
            val modelError = if (preferencesError != null) null else try { verify(); null }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { "${error.javaClass.simpleName}: ${error.message}" }
            StartupAuditResult(preferencesError, modelError).also { mutableResult.value = it }
        }.also { pending = it }
    }
}
/** One IO audit for every process entry: launcher, reading activity, shortcut, or service. */
object StartupModelAudit {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: StartupAuditJob? = null
    private fun job(context: Context): StartupAuditJob = job ?: synchronized(this) {
        job ?: context.applicationContext.let { app -> StartupAuditJob(scope,
            initialize = { UserPreferences.initialize(app) },
            verify = { ModelSourceBindings.verifyAtLaunch(app); Unit })
        }.also { job = it }
    }
    fun start(context: Context, retry: Boolean = false): Deferred<StartupAuditResult> = job(context).start(retry)
    suspend fun await(context: Context, retry: Boolean = false): StartupAuditResult = start(context, retry).await()
}
