package com.sal7one.transiber.benchmark

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Non-exported native owner. Completed jobs release handles without crashing this process. */
internal class BenchmarkWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    @Volatile private var verifiedWorker = false
    private var jobId: String? = null
    private var token: String? = null
    private var parent: Messenger? = null
    @Volatile private var deadline: BenchmarkWorkerDeadline? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "benchmark-hard-stop").apply { isDaemon = true }
    }
    private val parentDeath = IBinder.DeathRecipient { terminate() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message -> handle(message); true })

    override fun onCreate() {
        super.onCreate()
        check(isWorkerProcess(packageName)) { "Benchmark native owner must run outside the app process" }
        verifiedWorker = true
        // Independent of blocked JNI, with no result/Bundle allocation at expiry.
        watchdog.scheduleAtFixedRate({
            if (deadline?.remainingMillis()?.let { it <= 0 } == true) terminate()
        }, 50, 50, TimeUnit.MILLISECONDS)
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        if (message.sendingUid != Process.myUid()) return
        val id = message.data.getString("job").orEmpty()
        val secret = message.data.getString("token").orEmpty()
        if (!BenchmarkWorkerProtocol.validIdentity(id, secret)) return
        if (message.what == BenchmarkWorkerProtocol.HELLO) {
            if (jobId != null && (jobId != id || token != secret)) return
            val endpoint = message.replyTo ?: return
            val seconds = message.data.getInt("timeoutSeconds")
            if (seconds !in 5..20) { terminate(); return }
            if (jobId == null) {
                try { endpoint.binder.linkToDeath(parentDeath, 0) } catch (_: Exception) { terminate(); return }
                deadline = BenchmarkWorkerDeadline(seconds, SystemClock::elapsedRealtime,
                    BenchmarkWorkerDeadline.maximumBoundaries(BenchmarkWorkerProtocol.MAX_SAMPLES, 10))
            }
            jobId = id; token = secret; parent = endpoint
            reply(BenchmarkWorkerProtocol.READY) { putInt("pid", Process.myPid()) }
            return
        }
        if (jobId != id || token != secret || message.replyTo?.binder != parent?.binder) return
        when (message.what) {
            BenchmarkWorkerProtocol.RUN -> if (started.compareAndSet(false, true)) execute(id)
            BenchmarkWorkerProtocol.EXIT -> terminate()
            BenchmarkWorkerProtocol.RELEASE -> if (!started.get() || completed.get()) release() else terminate()
        }
    }

    private fun execute(id: String) = scope.launch {
        try {
            val request = BenchmarkWorkerProtocol.read(applicationContext, id)
            BenchmarkWorkerInitialization.initialize(applicationContext, request.candidate)
            var result: BenchmarkResult? = null
            LocalBenchmarkRunner(applicationContext).run(listOf(request.candidate), request.clip, request.text,
                request.referenceText, request.source, request.target, benchmarkInputs = request.inputs,
                compareAcceleration = false, resumeCompleted = false, measuredPasses = request.measuredPasses,
                persistResults = false, workerJisrDialect = request.jisrDialect, workerVoiceApproved = request.voiceApproved,
                attemptTimeoutSeconds = request.attemptTimeoutSeconds,
                onPhase = { phase ->
                    val clock = checkNotNull(deadline)
                    clock.advance(phase)
                    reply(BenchmarkWorkerProtocol.PHASE) {
                        putString("phase", phase.name); putLong("startedElapsedRealtime", clock.operationStartedMillis)
                    }
                },
                progress = { text -> reply(BenchmarkWorkerProtocol.PROGRESS) { putString("message", text.take(1024)) } },
                onResult = { row -> check(result == null) { "Worker returned more than one candidate result" }; result = row })
            val row = checkNotNull(result) { "Worker did not return a benchmark result" }
            BenchmarkWorkerProtocol.writeResult(BenchmarkWorkerProtocol.directory(applicationContext, id), row)
            // Returning includes native close()/closeAndJoin(). Only this state may release
            // native ownership while retaining a cached, idle OS process.
            val clean = row.terminationReason != "cleanup_error"
            completed.set(clean)
            if (clean) deadline?.finish()
            reply(BenchmarkWorkerProtocol.FINISHED) { putBoolean("requiresProcessExit", !clean) }
        } catch (error: BenchmarkWorkerTimeoutException) {
            terminate()
        } catch (error: Throwable) {
            reply(BenchmarkWorkerProtocol.FAILED) { putString("message", (error.message ?: error.toString()).take(2048)) }
            // Unknown failures may have interrupted cleanup; never acknowledge idle ownership.
            terminate()
        }
    }

    private fun release() {
        deadline?.finish(); deadline = null
        reply(BenchmarkWorkerProtocol.RELEASED)
        parent?.binder?.let { try { it.unlinkToDeath(parentDeath, 0) } catch (_: Exception) { } }
        parent = null; jobId = null; token = null
        started.set(false); completed.set(false)
    }

    private fun reply(what: Int, fill: Bundle.() -> Unit = {}) {
        try {
            parent?.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString("job", jobId); putString("token", token); fill() }
            })
        } catch (_: Exception) { terminate() }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        if (started.get() && !completed.get()) terminate()
        else if (jobId != null) release()
        return false
    }
    override fun onDestroy() {
        if (started.get() && !completed.get()) terminate()
        watchdog.shutdownNow(); scope.cancel()
        super.onDestroy()
    }
    private fun terminate() {
        // A successful self-exit avoids treating deliberate timeouts as SIGKILL/crashes.
        // halt() bypasses shutdown hooks/finalizers; a wedged JNI thread cannot delay it.
        if (verifiedWorker) Runtime.getRuntime().halt(0)
    }

    companion object {
        fun isWorkerProcess(packageName: String): Boolean =
            Application.getProcessName() == packageName + BenchmarkWorkerProtocol.PROCESS_SUFFIX
    }
}
