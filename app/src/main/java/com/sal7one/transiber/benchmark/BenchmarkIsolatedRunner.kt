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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** Native work stays outside the UI process; cleanup or process death is confirmed per job. */
internal class BenchmarkIsolatedRunner(context: Context) {
    private val app = context.applicationContext
    @Volatile private var active: Connection? = null

    fun cancel() { active?.requestTermination() }

    /** A failed shutdown remains owned; retry never permits overlapping native model work. */
    suspend fun recover(): Boolean {
        val connection = active ?: return true
        return try {
            withContext(NonCancellable) { connection.terminateAndAwaitDeath() }
            releaseConnection(connection)
            true
        } catch (_: BenchmarkWorkerReapException) { false }
    }

    suspend fun run(
        candidate: BenchmarkCandidate, clip: BenchmarkAudio.Clip?, text: String, referenceText: String?,
        source: String, target: String, benchmarkInputs: List<BenchmarkInput> = emptyList(),
        measuredPasses: Int = 3, attemptTimeoutSeconds: Int = 20, jisrDialect: String,
        progress: (String) -> Unit,
    ): BenchmarkResult {
        require(attemptTimeoutSeconds in 5..20)
        check(active == null) { "The previous benchmark worker still needs recovery" }
        candidate.voiceFamily?.let { com.sal7one.transiber.voice.LocalVoiceFamilies.requireAllowed(app, it, "model benchmarking") }
        val request = BenchmarkWorkerProtocol.Request(candidate, clip, text, referenceText, source, target,
            benchmarkInputs, measuredPasses, jisrDialect, voiceApproved = candidate.voiceFamily != null,
            attemptTimeoutSeconds = attemptTimeoutSeconds)
        val (id, _) = withContext(Dispatchers.IO) { BenchmarkWorkerProtocol.create(app, request) }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val deadline = BenchmarkWorkerDeadline(attemptTimeoutSeconds, SystemClock::elapsedRealtime,
            BenchmarkWorkerDeadline.maximumBoundaries(benchmarkInputs.size.coerceAtLeast(1), measuredPasses))
        val connection = Connection(id, token, attemptTimeoutSeconds, deadline, progress)
        active = connection
        try {
            return BenchmarkWorkerWatchdog.run(attemptTimeoutSeconds, connection,
                elapsedMs = deadline::operationElapsedMillis, deadline = deadline) {
                connection.bind()
                connection.ready.await()
                connection.start()
                connection.finished.await()
                withContext(Dispatchers.IO) {
                    BenchmarkWorkerProtocol.readResult(BenchmarkWorkerProtocol.directory(app, id)).also {
                        // Native close failure cannot be released as an idle cached process.
                        connection.requireProcessExit = it.terminationReason == "cleanup_error"
                    }
                }
            }
        } finally {
            if (connection.safeToRelease) releaseConnection(connection)
        }
    }

    private suspend fun releaseConnection(connection: Connection) {
        if (active === connection) active = null
        withContext(NonCancellable + Dispatchers.IO) { BenchmarkWorkerProtocol.directory(app, connection.id).deleteRecursively() }
    }

    private inner class Connection(
        val id: String, val token: String, val timeoutSeconds: Int,
        val deadline: BenchmarkWorkerDeadline, val progress: (String) -> Unit,
    ) : ServiceConnection, BenchmarkWorkerLifetime {
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val death = CompletableDeferred<Unit>()
        private val startedAtEpoch = System.currentTimeMillis()
        @Volatile private var worker: Messenger? = null
        @Volatile private var binder: IBinder? = null
        @Volatile private var workerPid: Int? = null
        @Volatile private var bound = false
        @Volatile private var stopping = false
        @Volatile private var began = false
        @Volatile private var completedNative = false
        @Volatile var requireProcessExit = false
        @Volatile var safeToRelease = false
        private val intent = Intent(app, BenchmarkWorkerService::class.java)
        private val deathRecipient = IBinder.DeathRecipient {
            death.complete(Unit)
            val expired = deadline.expired()
            if (!stopping && !completedNative) fail(if (expired != null)
                BenchmarkWorkerTimeoutException(timeoutSeconds, expired.elapsedMillis, expired.phase)
            else {
                val detail = workerPid?.let { BenchmarkWorkerExitDiagnostics.describe(app, it, startedAtEpoch) }
                BenchmarkWorkerException("Model worker exited before returning a result" +
                    (detail?.let { ": $it" } ?: "; Android did not provide an exit reason"))
            })
        }
        private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != Process.myUid() || message.data.getString("job") != id || message.data.getString("token") != token) {
                fail(BenchmarkWorkerException("Rejected unauthenticated benchmark worker response"))
                return@Handler true
            }
            try {
                when (message.what) {
                    BenchmarkWorkerProtocol.READY -> {
                        val pid = message.data.getInt("pid", -1)
                        // Android process inventory is not complete on every vendor build.
                        // The explicit non-exported service, same UID, and random job secret
                        // authenticate this endpoint even when inventory has no matching row.
                        if (!validHandshakePid(pid)) fail(BenchmarkWorkerException("Benchmark worker did not identify a separate app-owned process"))
                        else { workerPid = pid; if (stopping) requestTermination() else ready.complete(Unit) }
                    }
                    BenchmarkWorkerProtocol.PHASE -> if (!stopping) {
                        val phase = BenchmarkWorkerPhase.valueOf(message.data.getString("phase").orEmpty())
                        deadline.advance(phase, message.data.getLong("startedElapsedRealtime"))
                    }
                    BenchmarkWorkerProtocol.PROGRESS -> if (!stopping) progress(message.data.getString("message").orEmpty().take(1024))
                    BenchmarkWorkerProtocol.FINISHED -> {
                        requireProcessExit = message.data.getBoolean("requiresProcessExit")
                        completedNative = true
                        deadline.finish()
                        finished.complete(Unit)
                    }
                    BenchmarkWorkerProtocol.RELEASED -> released.complete(Unit)
                    BenchmarkWorkerProtocol.TIMED_OUT -> fail(BenchmarkWorkerTimeoutException(timeoutSeconds, deadline.operationElapsedMillis(), deadline.phase))
                    BenchmarkWorkerProtocol.FAILED -> fail(BenchmarkWorkerException(message.data.getString("message").orEmpty().take(2048).ifBlank { "Model worker failed" }))
                }
            } catch (error: Exception) { fail(error) }
            true
        })

        suspend fun bind() = withContext(Dispatchers.Main.immediate) {
            if (stopping) throw CancellationException("Benchmark stopped")
            // This is an explicit user benchmark. Use ordinary bound-service scheduling;
            // WAIVE_PRIORITY would handicap live performance as background work. Do not
            // promote the worker above its client with IMPORTANT or ABOVE_CLIENT flags.
            bound = app.bindService(intent, this@Connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Android could not bind the isolated benchmark service" }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try {
                service.linkToDeath(deathRecipient, 0)
                worker = Messenger(service)
                // Even cancellation must register this job/token before EXIT is accepted.
                send(BenchmarkWorkerProtocol.HELLO)
            } catch (error: Exception) { fail(BenchmarkWorkerException("Benchmark worker connection failed", error)) }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected() }
        override fun onBindingDied(name: ComponentName) { disconnected() }
        override fun onNullBinding(name: ComponentName) { fail(BenchmarkWorkerException("Benchmark worker rejected its private binding")) }
        private fun disconnected() {
            if (binder?.isBinderAlive == false) deathRecipient.binderDied()
            else if (!stopping) fail(BenchmarkWorkerException("Android disconnected the benchmark worker"))
        }

        fun start() {
            check(!stopping) { "Benchmark stopped" }
            check(workerPid != null) { "Cannot start an unauthenticated benchmark worker" }
            began = true
            send(BenchmarkWorkerProtocol.RUN)
        }

        private fun send(what: Int) {
            checkNotNull(worker).send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString("job", id); putString("token", token); putInt("timeoutSeconds", timeoutSeconds) }
                replyTo = reply
            })
        }
        private fun fail(error: Exception) { ready.completeExceptionally(error); finished.completeExceptionally(error) }

        fun requestTermination() {
            stopping = true
            // Always ask the authenticated endpoint to exit, regardless of the optional
            // ActivityManager inventory. Previously a missing PID row skipped both paths.
            try { if (binder?.isBinderAlive == true) send(if (began) BenchmarkWorkerProtocol.EXIT else BenchmarkWorkerProtocol.RELEASE) } catch (_: Exception) { }
        }

        override suspend fun terminateAndAwaitDeath() {
            try { releaseOwnedWorker() }
            catch (error: Throwable) {
                // Even vendor/Binder/ActivityManager failures must retain the ownership
                // quarantine unless cleanup/death was already proved. Generic errors
                // must never let the controller start an overlapping model.
                if (safeToRelease) return
                if (error is BenchmarkWorkerReapException) throw error
                throw BenchmarkWorkerReapException("Android could not confirm model worker shutdown. Comparison is paused; use Retry cleanup before running another local model.", error)
            }
        }

        private suspend fun releaseOwnedWorker() {
            if (safeToRelease) return
            val endpoint = binder
            if (!began) {
                // No RUN was ever sent, so even a late/idle Binder cannot own native work.
                stopping = true
                safeToRelease = true
                detach()
                return
            }
            if (!stopping && !requireProcessExit && (completedNative || !began)) {
                try { send(BenchmarkWorkerProtocol.RELEASE) } catch (_: Exception) { }
                if (awaitProof(1_500, allowIdle = true)) { finishRelease(); return }
            }
            requestTermination()
            if (!awaitProof(3_500, allowIdle = false)) {
                // Fallback only targets a PID independently verified in public process
                // inventory and still tied to this same live Binder, never a guessed PID.
                workerPid?.let { pid -> if (endpoint?.isBinderAlive == true && ownsWorker(pid)) Process.killProcess(pid) }
                if (!awaitProof(2_000, allowIdle = false)) {
                    throw BenchmarkWorkerReapException("Android has not confirmed model worker shutdown. Comparison is paused; use Retry cleanup before running another local model.")
                }
            }
            finishRelease()
        }

        private suspend fun awaitProof(milliseconds: Long, allowIdle: Boolean): Boolean =
            withTimeoutOrNull(milliseconds) {
                while (true) {
                    if ((allowIdle && released.isCompleted) || death.isCompleted) return@withTimeoutOrNull true
                    val endpoint = binder
                    // pingBinder() is a synchronous remote transaction; a hung worker can
                    // block it beyond this coroutine timeout. Query local liveness only.
                    if (endpoint != null && !endpoint.isBinderAlive) {
                        death.complete(Unit)
                        return@withTimeoutOrNull true
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") false
            } == true

        private suspend fun finishRelease() {
            safeToRelease = true
            stopping = true
            detach()
            binder?.let { try { it.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) { } }
        }

        private suspend fun detach() = withContext(Dispatchers.Main.immediate) {
            if (bound) { app.unbindService(this@Connection); bound = false }
        }

        private fun validHandshakePid(pid: Int): Boolean {
            if (pid <= 0 || pid == Process.myPid()) return false
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val recorded = manager.runningAppProcesses.orEmpty().singleOrNull { it.pid == pid } ?: return true
            return BenchmarkWorkerIdentity.matches(recorded.pid, recorded.uid, recorded.processName, app.packageName, pid, Process.myUid(), Process.myPid())
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

/** Process inventory is used for a forced PID kill, never required for normal Binder release. */
internal object BenchmarkWorkerIdentity {
    fun matches(pid: Int, uid: Int, processName: String, packageName: String,
        expectedPid: Int, expectedUid: Int, parentPid: Int): Boolean =
        pid > 0 && pid != parentPid && pid == expectedPid && uid == expectedUid &&
            processName == packageName + BenchmarkWorkerProtocol.PROCESS_SUFFIX
}
