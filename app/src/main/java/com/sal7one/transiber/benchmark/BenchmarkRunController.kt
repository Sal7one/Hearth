package com.sal7one.transiber.benchmark

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sal7one.transiber.runtime.LocalModelPolicy
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Inputs are loaded per batch, rather than holding every language's decoded audio at once. */
internal data class BenchmarkBatch(
    val models: List<BenchmarkCandidate>, val source: String, val target: String,
    val clip: BenchmarkAudio.Clip? = null, val text: String = "", val referenceText: String? = null,
    val compareAcceleration: Boolean = false, val resumeCompleted: Boolean = false, val measuredPasses: Int = 3,
    val suite: BenchmarkSuite? = null, val cases: List<BenchmarkCase> = emptyList(),
    val attemptTimeoutSeconds: Int = 20,
)

internal data class BenchmarkRunState(
    val runId: String? = null, val title: String = "Model comparison", val running: Boolean = false,
    val stopping: Boolean = false, val status: String = "", val error: String? = null,
    val completedRows: Int = 0, val failedRows: Int = 0, val revision: Long = 0,
)

/** Application-owned work; navigating away never cancels a user-started comparison. */
internal object BenchmarkRunController {
    private data class Plan(val id: String, val app: Context, val batches: List<BenchmarkBatch>)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(BenchmarkRunState())
    val state = mutableState.asStateFlow()
    private var pending: Plan? = null
    private var work: Job? = null
    private var runner: BenchmarkIsolatedRunner? = null

    fun start(context: Context, batches: List<BenchmarkBatch>, title: String = "Model comparison") {
        val app = context.applicationContext
        check(LocalModelPolicy.allowed(app)) { "Local benchmarks are unavailable on this device" }
        require(batches.isNotEmpty() && batches.all { it.models.isNotEmpty() }) { "Select models to compare first" }
        require(batches.all { it.measuredPasses in 3..10 }) { "Choose 3–10 measured passes" }
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            check(!state.value.running) { "A benchmark is already running. Stop it before starting another." }
            check(LocalWorkGate.owner.value == null) { "${LocalWorkGate.owner.value} is still using the local engines" }
            pending = Plan(id, app, batches.map { it.copy(models = it.models.toList(), cases = it.cases.toList()) })
            mutableState.value = BenchmarkRunState(id, title, running = true, status = "Starting comparison…",
                revision = state.value.revision + 1)
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, BenchmarkRunService::class.java)
                .setAction(BenchmarkRunService.ACTION_START).putExtra(BenchmarkRunService.EXTRA_RUN, id))
        } catch (error: Exception) {
            failStart(id, error.message ?: error.toString())
            throw error
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun execute(id: String): Boolean {
        synchronized(lock) {
            val plan = pending?.takeIf { it.id == id && state.value.running } ?: return false
            if (work != null) return false
            val engine = BenchmarkIsolatedRunner(plan.app)
            runner = engine
            pending = null
            // Enter finally even if Stop arrives before the first dispatch.
            work = scope.launch(start = CoroutineStart.ATOMIC) {
                var lease: LocalWorkGate.Lease? = null
                var releaseLease = true
                try {
                    lease=LocalWorkGate.acquire("Benchmark")
                    val disqualified=mutableMapOf<String,String>()
                    val dialect=com.sal7one.transiber.translation.ConversationTranslationSettings.jisrDialect(plan.app)
                    val results=BenchmarkResults(plan.app)
                    plan.batches.forEachIndexed { index, batch ->
                        ensureActive()
                        val inputs = withContext(Dispatchers.IO) {
                            batch.cases.map { sample ->
                                BenchmarkInput(sample.id, sample.text, sample.reference,
                                    if (sample.speech) checkNotNull(batch.suite).audio(plan.app, sample) else null,
                                    sample.silenceMs > 0)
                            }
                        }
                        BenchmarkAttemptRecord.validate(batch,inputs)
                        val candidates=BenchmarkAccelerationPlan.resolve(plan.app,batch.models,batch.compareAcceleration)
                        val history=if(batch.resumeCompleted)withContext(Dispatchers.IO) {results.load()} else emptyList()
                        for((modelIndex,candidate) in candidates.withIndex()) {
                            ensureActive()
                            val prefix="${index+1}/${plan.batches.size} · ${modelIndex+1}/${candidates.size} · ${candidate.label}"
                            val key=BenchmarkAttemptPolicy.disqualificationKey(candidate)
                            if(key in disqualified) {
                                update(id) {it.copy(status="$prefix: skipped; disqualified earlier in this comparison")}
                                continue
                            }
                            val configuration=BenchmarkAttemptRecord.configuration(plan.app,candidate,batch.measuredPasses,dialect,batch.attemptTimeoutSeconds)
                            val hash=BenchmarkAttemptRecord.inputHash(inputs,batch.clip,batch.text,batch.referenceText,batch.source,batch.target,candidate.task)
                            if(history.any {BenchmarkExecutionMetadata.canResume(it,candidate,hash,configuration,batch.source,
                                if(candidate.task==BenchmarkTask.TRANSLATION)batch.target else "",BenchmarkAttemptRecord.device(),batch.measuredPasses)}) {
                                update(id) {it.copy(status="$prefix: kept completed result")}
                                continue
                            }
                            val begun=android.os.SystemClock.elapsedRealtime()
                            val result=try {
                                engine.run(candidate,batch.clip,batch.text,batch.referenceText,batch.source,batch.target,
                                    benchmarkInputs=inputs,measuredPasses=batch.measuredPasses,
                                    attemptTimeoutSeconds=BenchmarkAttemptPolicy.seconds(batch.attemptTimeoutSeconds),jisrDialect=dialect,
                                    progress={message -> update(id) {it.copy(status="$prefix: $message")}}).copy(runId=id)
                            } catch(error: BenchmarkWorkerReapException) {
                                // Do not permit another local workload when worker ownership is lost.
                                releaseLease=false
                                throw error
                            } catch(cancelled: CancellationException) {throw cancelled}
                            catch(error: BenchmarkWorkerTimeoutException) {
                                val message=BenchmarkAttemptPolicy.timeoutMessage(batch.attemptTimeoutSeconds)
                                disqualified[key]=message
                                BenchmarkAttemptRecord.failed(plan.app,id,candidate,batch,inputs,dialect,message,"deadline")
                            } catch(error: BenchmarkWorkerException) {
                                val message="Disqualified: ${error.message ?: error}"
                                disqualified[key]=message
                                BenchmarkAttemptRecord.failed(plan.app,id,candidate,batch,inputs,dialect,message,"worker_crash")
                            } catch(error: Exception) {
                                BenchmarkAttemptRecord.failed(plan.app,id,candidate,batch,inputs,dialect,error.message ?: error.toString(),"error")
                            } catch(error: LinkageError) {
                                BenchmarkAttemptRecord.failed(plan.app,id,candidate,batch,inputs,dialect,error.message ?: error.toString(),"error")
                            }
                            val measured=result.copy(runtime=result.runtime+"; disposable benchmark process; background worker priority; attempt cap ${BenchmarkAttemptPolicy.seconds(batch.attemptTimeoutSeconds)}s",
                                attemptTimeoutSeconds=BenchmarkAttemptPolicy.seconds(batch.attemptTimeoutSeconds),
                                attemptElapsedMs=android.os.SystemClock.elapsedRealtime()-begun)
                            val accepted=if(measured.error!=null || measured.terminationReason!=null)
                                BenchmarkAttemptPolicy.failureResult(measured,measured.error ?: "Disqualified: ${measured.terminationReason}") else measured
                            // The worker has exited before any row is accepted or another model opens.
                            withContext(Dispatchers.IO) {results.append(accepted)}
                            update(id) {it.copy(completedRows=it.completedRows+1,
                                failedRows=it.failedRows+if(result.error!=null)1 else 0,revision=it.revision+1)}
                        }
                    }
                    update(id) { it.copy(status = if (it.failedRows == 0) "Comparison complete · results saved"
                        else "Comparison complete · ${it.failedRows} failed rows; see Results") }
                } catch (cancelled: CancellationException) {
                    update(id) { it.copy(status = "Comparison stopped · completed results saved") }
                    throw cancelled
                } catch (error: Exception) {
                    update(id) { it.copy(status = "Comparison stopped", error = error.message ?: error.toString()) }
                } catch (error: LinkageError) {
                    update(id) { it.copy(status = "Comparison stopped", error = error.message ?: error.toString()) }
                } finally {
                    if(releaseLease)lease?.close()
                    // A worker is reaped before the parent inference lease is released.
                    synchronized(lock) {
                        if (state.value.runId == id) {
                            work = null; runner = null
                            mutableState.update { it.copy(running = false, stopping = false, revision = it.revision + 1) }
                        }
                    }
                }
            }
            return true
        }
    }

    fun stop(id: String? = state.value.runId) {
        val engine: BenchmarkIsolatedRunner?
        synchronized(lock) {
            if (id == null || state.value.runId != id || !state.value.running) return
            mutableState.update { it.copy(stopping = true, status = "Stopping after native cleanup…") }
            pending = null
            engine = runner
            if (work == null) {
                mutableState.update { it.copy(running = false, stopping = false, status = "Comparison stopped", revision = it.revision + 1) }
            } else work!!.cancel()
        }
        scope.launch(Dispatchers.IO) {
            try { engine?.cancel() }
            catch (error: Exception) { update(id) { it.copy(error = error.message ?: error.toString()) } }
            catch (error: LinkageError) { update(id) { it.copy(error = error.message ?: error.toString()) } }
        }
    }

    internal fun failStart(id: String, message: String) {
        stop(id)
        update(id) { it.copy(error = message, status = "Cannot start comparison", revision = it.revision + 1) }
    }

    private fun update(id: String?, change: (BenchmarkRunState) -> BenchmarkRunState) {
        mutableState.update { if (it.runId == id) change(it) else it }
    }
}
