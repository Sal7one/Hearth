package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
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
import androidx.test.core.app.ApplicationProvider
import com.sal7one.common_jni.speech.SpeechProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/** Executes the production client connection and private-file protocol with a fake service endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BenchmarkIsolatedRunnerTest {
    private val base: Context get() = ApplicationProvider.getApplicationContext()
    @After fun cleanup() { BenchmarkWorkerProtocol.root(base).deleteRecursively() }

    private inner class FakeWorkerContext(val holdConnection: Boolean = false,
        val hang: Boolean = false, val cleanupError: Boolean = false) : ContextWrapper(base) {
        val messages = mutableListOf<Int>()
        var unbinds = 0
        var bindFlags = -1
        @Volatile var connection: ServiceConnection? = null
        @Volatile var alive = true
        private val service = Messenger(Handler(Looper.getMainLooper()) { message ->
            messages += message.what
            val parent = message.replyTo
            val job = message.data.getString("job")!!
            val token = message.data.getString("token")!!
            fun reply(what: Int, fill: Bundle.() -> Unit = {}) {
                parent.send(Message.obtain(null, what).apply {
                    data = Bundle().apply { putString("job", job); putString("token", token); fill() }
                })
            }
            when (message.what) {
                BenchmarkWorkerProtocol.HELLO -> reply(BenchmarkWorkerProtocol.READY) { putInt("pid", Process.myPid() + 100_000) }
                BenchmarkWorkerProtocol.RUN -> {
                    for (phase in listOf(BenchmarkWorkerPhase.LOAD, BenchmarkWorkerPhase.INFERENCE,
                        BenchmarkWorkerPhase.CLEANUP, BenchmarkWorkerPhase.RESULT)) {
                        reply(BenchmarkWorkerProtocol.PHASE) { putString("phase", phase.name); putLong("startedElapsedRealtime", SystemClock.elapsedRealtime()) }
                    }
                    if (!hang) {
                        BenchmarkWorkerProtocol.writeResult(BenchmarkWorkerProtocol.directory(this, job),
                            BenchmarkResult("run", 1, "Model", "model@cpu", "speech", "input", "en", "", 1_000,
                                10.0, listOf(2.0, 2.0, 2.0), listOf("hello"), "CPU", listOf(1.0), "phone",
                                error = if (cleanupError) "Native close failed" else null,
                                terminationReason = if (cleanupError) "cleanup_error" else null))
                        reply(BenchmarkWorkerProtocol.FINISHED) { putBoolean("requiresProcessExit", cleanupError) }
                    }
                }
                BenchmarkWorkerProtocol.RELEASE -> reply(BenchmarkWorkerProtocol.RELEASED)
                BenchmarkWorkerProtocol.EXIT -> if (hang || cleanupError) alive = false
                    else error("A successful or never-started model must not be killed")
            }
            true
        })
        private val endpoint = object : IBinder by service.binder {
            override fun isBinderAlive(): Boolean = alive
            override fun pingBinder(): Boolean = throw AssertionError("Liveness must not issue a blocking Binder transaction")
        }
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindFlags = flags; connection = conn
            if (!holdConnection) conn.onServiceConnected(ComponentName(this, BenchmarkWorkerService::class.java), endpoint)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { assertSame(connection, conn); unbinds++ }
    }

    private fun candidate() = BenchmarkCandidate("imported", "Qwen", "Speech / UTTERANCE_WINDOWED",
        setOf("en"), file = File(base.filesDir, "speech-models/imported"), profile = SpeechProfile.QWEN3_ASR_0_6B,
        acceleration = "cpu", artifactFingerprint = "a".repeat(64))

    private fun pumpUntil(done: () -> Boolean) {
        val expiry = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < expiry) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Client protocol did not finish", done())
    }

    @Test fun successfulJobsReleaseWithoutPidInventoryOrKillingAndAllowNextModel() {
        val context = FakeWorkerContext()
        val runner = BenchmarkIsolatedRunner(context)
        repeat(2) {
            val result = CoroutineScope(Dispatchers.Default).async {
                runner.run(candidate(), null, "hello", null, "en", "", jisrDialect = "ar", progress = {})
            }
            pumpUntil { result.isCompleted }
            assertEquals("hello", runBlocking { result.await() }.texts.single())
        }
        assertEquals(listOf(1, 3, 10, 1, 3, 10), context.messages)
        assertEquals(2, context.unbinds)
        assertEquals(Context.BIND_AUTO_CREATE, context.bindFlags)
        assertTrue(BenchmarkWorkerProtocol.root(base).listFiles().orEmpty().isEmpty())
        assertTrue(runBlocking { runner.recover() })
    }

    @Test fun cancellationBeforeConnectionNeverQuarantinesAnIdleWorker() {
        val context = FakeWorkerContext(holdConnection = true)
        val runner = BenchmarkIsolatedRunner(context)
        val work = CoroutineScope(Dispatchers.Default).async {
            runner.run(candidate(), null, "hello", null, "en", "", jisrDialect = "ar", progress = {})
        }
        pumpUntil { context.connection != null }
        runner.cancel(); work.cancel()
        pumpUntil { work.isCompleted }
        runBlocking { work.cancelAndJoin() }
        assertEquals(1, context.unbinds)
        assertTrue(context.messages.isEmpty())
        assertTrue(runBlocking { runner.recover() })
        assertTrue(BenchmarkWorkerProtocol.root(base).listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelledNativeWorkRequiresObservedDeathWithoutABlockingBinderProbe() {
        val context = FakeWorkerContext(hang = true)
        val runner = BenchmarkIsolatedRunner(context)
        val work = CoroutineScope(Dispatchers.Default).async {
            runner.run(candidate(), null, "hello", null, "en", "", jisrDialect = "ar", progress = {})
        }
        pumpUntil { BenchmarkWorkerProtocol.RUN in context.messages }
        runner.cancel(); work.cancel()
        pumpUntil { work.isCompleted }
        runBlocking { work.cancelAndJoin() }
        assertTrue(BenchmarkWorkerProtocol.EXIT in context.messages)
        assertFalse(context.alive)
        assertEquals(1, context.unbinds)
        assertTrue(runBlocking { runner.recover() })
    }

    @Test fun cleanupFailureRequiresProcessExitInsteadOfAcceptingIdleRelease() {
        val context = FakeWorkerContext(cleanupError = true)
        val runner = BenchmarkIsolatedRunner(context)
        val work = CoroutineScope(Dispatchers.Default).async {
            runner.run(candidate(), null, "hello", null, "en", "", jisrDialect = "ar", progress = {})
        }
        pumpUntil { work.isCompleted }
        assertEquals("cleanup_error", runBlocking { work.await() }.terminationReason)
        assertTrue(BenchmarkWorkerProtocol.EXIT in context.messages)
        assertFalse(BenchmarkWorkerProtocol.RELEASE in context.messages)
        assertFalse(context.alive)
        assertEquals(1, context.unbinds)
    }
}
