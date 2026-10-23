package com.sal7one.transiber.benchmark

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** Each candidate owns a separate process. Native hangs cannot hold the app or the next model. */
internal class BenchmarkIsolatedRunner(context: Context) {
    private val app = context.applicationContext
    @Volatile private var active: Connection? = null

    fun cancel() { active?.requestTermination() }

    suspend fun run(
        candidate: BenchmarkCandidate, clip: BenchmarkAudio.Clip?, text: String, referenceText: String?,
        source: String, target: String, benchmarkInputs: List<BenchmarkInput> = emptyList(),
        measuredPasses: Int = 3, attemptTimeoutSeconds: Int = 20, jisrDialect: String,
        progress: (String) -> Unit,
    ): BenchmarkResult {
        require(attemptTimeoutSeconds in 5..20)
        check(active == null) { "A benchmark worker is already running" }
        val started = SystemClock.elapsedRealtime()
        candidate.voiceFamily?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(app, it, "model benchmarking") }
        val request = BenchmarkWorkerProtocol.Request(candidate, clip, text, referenceText, source, target,
            benchmarkInputs, measuredPasses, jisrDialect, voiceApproved = candidate.voiceFamily != null,
            attemptTimeoutSeconds = attemptTimeoutSeconds)
        val (id, directory) = withContext(Dispatchers.IO) { BenchmarkWorkerProtocol.create(app, request) }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val connection = Connection(id, token, attemptTimeoutSeconds, started, progress)
        active = connection
        try {
            return BenchmarkWorkerWatchdog.run(attemptTimeoutSeconds, connection,
                remainingMs = attemptTimeoutSeconds * 1000L - (SystemClock.elapsedRealtime() - started),
                elapsedMs = { SystemClock.elapsedRealtime() - started }) {
                connection.bind()
                connection.ready.await()
                connection.start()
                connection.finished.await()
                withContext(Dispatchers.IO) { BenchmarkWorkerProtocol.readResult(directory) }
            }
        } finally {
            active = null
            withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    private inner class Connection(
        val id: String, val token: String, val timeoutSeconds: Int, val startedAt: Long, val progress: (String) -> Unit,
    ) : ServiceConnection, BenchmarkWorkerLifetime {
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        private val death = CompletableDeferred<Unit>()
        @Volatile private var worker: Messenger? = null
        @Volatile private var binder: IBinder? = null
        @Volatile private var workerPid: Int? = null
        @Volatile private var bound = false
        @Volatile private var stopping = false
        @Volatile private var began = false
        private val intent = Intent(app, BenchmarkWorkerService::class.java)
        private val deathRecipient = IBinder.DeathRecipient {
            death.complete(Unit)
            fail(if (SystemClock.elapsedRealtime() - startedAt >= timeoutSeconds * 1000L)
                BenchmarkWorkerTimeoutException(timeoutSeconds, SystemClock.elapsedRealtime() - startedAt)
            else BenchmarkWorkerException("Isolated model process exited before returning a result (native crash or Android memory pressure)"))
        }
        private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != Process.myUid() || message.data.getString("job") != id || message.data.getString("token") != token) {
                fail(BenchmarkWorkerException("Rejected unauthenticated benchmark worker response"))
                return@Handler true
            }
            when (message.what) {
                BenchmarkWorkerProtocol.READY -> {
                    val pid = message.data.getInt("pid", -1)
                    if (!ownsWorker(pid)) {
                        fail(BenchmarkWorkerException("Benchmark worker did not identify a separate app-owned process"))
                    } else {
                        workerPid = pid
                        if (stopping) requestTermination() else ready.complete(Unit)
                    }
                }
                BenchmarkWorkerProtocol.PROGRESS -> if (!stopping) progress(message.data.getString("message").orEmpty().take(1024))
                BenchmarkWorkerProtocol.FINISHED -> finished.complete(Unit)
                BenchmarkWorkerProtocol.TIMED_OUT -> fail(BenchmarkWorkerTimeoutException(timeoutSeconds, SystemClock.elapsedRealtime() - startedAt))
                BenchmarkWorkerProtocol.FAILED -> fail(BenchmarkWorkerException(message.data.getString("message").orEmpty().take(2048).ifBlank { "Isolated model process failed" }))
            }
            true
        })

        suspend fun bind() = withContext(Dispatchers.Main.immediate) {
            if (stopping) throw CancellationException("Benchmark stopped")
            // Do not let a large worker inherit the UI/foreground service's OOM priority.
            // Android may discard this disposable process before sacrificing the app itself.
            bound = app.bindService(intent, this@Connection, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND or Context.BIND_WAIVE_PRIORITY)
            check(bound) { "Android could not bind the isolated benchmark service" }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try {
                service.linkToDeath(deathRecipient, 0)
                worker = Messenger(service)
                send(BenchmarkWorkerProtocol.HELLO)
            } catch (error: Exception) { fail(BenchmarkWorkerException("Benchmark worker connection failed", error)) }
        }
        override fun onServiceDisconnected(name: ComponentName) { deathRecipient.binderDied() }
        override fun onBindingDied(name: ComponentName) { deathRecipient.binderDied() }
        override fun onNullBinding(name: ComponentName) { fail(BenchmarkWorkerException("Benchmark worker rejected its private binding")) }

        fun start() {
            check(!stopping) { "Benchmark stopped" }
            check(workerPid != null) { "Cannot start an unauthenticated benchmark worker" }
            began = true
            send(BenchmarkWorkerProtocol.RUN)
        }

        private fun send(what: Int) {
            checkNotNull(worker).send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putString("job", id); putString("token", token)
                    putInt("timeoutSeconds", timeoutSeconds); putLong("deadlineElapsedRealtime", startedAt + timeoutSeconds * 1000L)
                }
                replyTo = reply
            })
        }
        private fun fail(error: Exception) { ready.completeExceptionally(error); finished.completeExceptionally(error) }

        fun requestTermination() {
            stopping = true
            // PID came from the explicit, non-exported service handshake. Recheck UID to
            // reject a recycled/non-app PID, and never target the UI/foreground-service PID.
            workerPid?.let { pid ->
                if (binder?.isBinderAlive == true && ownsWorker(pid)) Process.killProcess(pid)
            }
        }

        override suspend fun terminateAndAwaitDeath() {
            requestTermination()
            withContext(Dispatchers.Main.immediate) {
                if (workerPid == null && binder?.isBinderAlive == true) {
                    try { send(BenchmarkWorkerProtocol.EXIT) } catch (_: Exception) { /* death is checked below */ }
                }
                if (bound) {
                    app.unbindService(this@Connection)
                    bound = false
                }
                // onUnbind also self-terminates an idle worker whose handshake timed out.
                app.stopService(intent)
            }
            val endpoint = binder
            if (endpoint != null && endpoint.isBinderAlive && withTimeoutOrNull(5_000) { death.await(); true } != true) {
                throw BenchmarkWorkerReapException("Benchmark worker termination could not be confirmed; comparison stopped to avoid overlapping native work. Restart Hearth before running more local models.")
            }
            if (began && endpoint == null) throw BenchmarkWorkerReapException("Lost benchmark worker ownership before termination could be verified. Restart Hearth before running more local models.")
            endpoint?.let { try { it.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) { } }
        }

        private fun ownsWorker(pid: Int): Boolean {
            if (pid <= 0 || pid == Process.myPid()) return false
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.runningAppProcesses.orEmpty().any {
                BenchmarkWorkerIdentity.matches(it.pid, it.uid, it.processName, app.packageName, pid, Process.myUid(), Process.myPid())
            }
        }
    }
}

/** Public Android process inventory plus the authenticated Binder handshake; never a guessed PID. */
internal object BenchmarkWorkerIdentity {
    fun matches(pid: Int, uid: Int, processName: String, packageName: String,
        expectedPid: Int, expectedUid: Int, parentPid: Int): Boolean =
        pid > 0 && pid != parentPid && pid == expectedPid && uid == expectedUid &&
            processName == packageName + BenchmarkWorkerProtocol.PROCESS_SUFFIX
}
