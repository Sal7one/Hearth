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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable, non-exported native owner. Only the foreground benchmark controller binds it. */
internal class BenchmarkWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    @Volatile private var verifiedWorker = false
    private var jobId: String? = null
    private var token: String? = null
    private var parent: Messenger? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "benchmark-hard-stop").apply { isDaemon = true }
    }
    private val parentDeath = IBinder.DeathRecipient { terminate() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        handle(message)
        true
    })

    override fun onCreate() {
        super.onCreate()
        check(isWorkerProcess(packageName)) { "Benchmark native owner must run outside the app process" }
        verifiedWorker = true
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
            val remaining = message.data.getLong("deadlineElapsedRealtime") - SystemClock.elapsedRealtime()
            if (seconds !in 5..20 || remaining !in 1..(seconds * 1000L)) { terminate(); return }
            // An independent Java thread still runs when native inference monopolizes its
            // calling thread. A dead parent Binder also kills this native owner immediately.
            if (jobId == null) {
                try { endpoint.binder.linkToDeath(parentDeath, 0) } catch (_: Exception) { terminate(); return }
                // Do not allocate a best-effort result/Bundle before killing: this timer
                // must still terminate a model when its process is out of Java heap.
                watchdog.schedule({ terminate() }, remaining, TimeUnit.MILLISECONDS)
            }
            jobId = id; token = secret; parent = endpoint
            reply(BenchmarkWorkerProtocol.READY) { putInt("pid", Process.myPid()) }
            return
        }
        if (jobId != id || token != secret || message.replyTo?.binder != parent?.binder) return
        when (message.what) {
            BenchmarkWorkerProtocol.RUN -> if (started.compareAndSet(false, true)) execute(id)
            BenchmarkWorkerProtocol.EXIT -> terminate()
        }
    }

    private fun execute(id: String) = scope.launch {
        // Lower this dispatch thread's priority. The service binding separately gives the
        // entire disposable process lower memory importance than the visible application.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val request = BenchmarkWorkerProtocol.read(applicationContext, id)
            initializeBenchmarkPlatform(applicationContext, request.candidate)
            var result: BenchmarkResult? = null
            LocalBenchmarkRunner(applicationContext).run(listOf(request.candidate), request.clip, request.text,
                request.referenceText, request.source, request.target, benchmarkInputs = request.inputs,
                compareAcceleration = false, resumeCompleted = false, measuredPasses = request.measuredPasses,
                persistResults = false, workerJisrDialect = request.jisrDialect, workerVoiceApproved = request.voiceApproved,
                attemptTimeoutSeconds = request.attemptTimeoutSeconds,
                progress = { text -> reply(BenchmarkWorkerProtocol.PROGRESS) { putString("message", text.take(1024)) } },
                onResult = { row -> check(result == null) { "Worker returned more than one candidate result" }; result = row })
            BenchmarkWorkerProtocol.writeResult(BenchmarkWorkerProtocol.directory(applicationContext, id),
                checkNotNull(result) { "Worker did not return a benchmark result" })
            reply(BenchmarkWorkerProtocol.FINISHED)
        } catch (error: Throwable) {
            // A native crash/OOM can kill the process before this point; Binder death is the
            // parent's authoritative failure signal for those cases.
            reply(BenchmarkWorkerProtocol.FAILED) { putString("message", (error.message ?: error.toString()).take(2048)) }
        }
        // Stay alive only until the parent observes the result and confirms process death.
    }

    private fun reply(what: Int, fill: Bundle.() -> Unit = {}) {
        try {
            parent?.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString("job", jobId); putString("token", token); fill() }
            })
        } catch (_: Exception) { terminate() }
    }
    override fun onUnbind(intent: Intent?): Boolean { terminate(); return false }
    override fun onDestroy() { watchdog.shutdownNow(); super.onDestroy(); terminate() }
    private fun terminate() {
        if (verifiedWorker) Process.killProcess(Process.myPid())
    }

    companion object {
        fun isWorkerProcess(packageName: String): Boolean =
            Application.getProcessName() == packageName + BenchmarkWorkerProtocol.PROCESS_SUFFIX
    }
}
