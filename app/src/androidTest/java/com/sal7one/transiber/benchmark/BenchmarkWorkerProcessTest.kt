package com.sal7one.transiber.benchmark

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.transiber.runtime.LocalModelPolicy
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Exercises the production service in its real Android subprocess, including Messenger
 * caller identity, private-file IPC, process bootstrap and release between requests.
 * No weights or network are needed: deliberately absent/invalid private models must
 * return honest initialization failures without losing worker ownership.
 *
 * This is lifecycle coverage, not evidence that a speech model performed inference.
 * Run only in an owner-authorized Android test environment.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkWorkerProcessTest {
    private lateinit var context: Context
    private lateinit var models: File
    private var lease: LocalWorkGate.Lease? = null
    private val runners = mutableListOf<BenchmarkIsolatedRunner>()

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assumeTrue("Local models are disabled on TVs", LocalModelPolicy.allowed(context))
        assumeTrue("Another local workload is active", LocalWorkGate.owner.value == null)
        lease = LocalWorkGate.acquire("Benchmark worker process test")
        models = File(context.filesDir, "benchmark-process-test-${UUID.randomUUID()}")
        check(models.mkdirs()) { "Could not create private test model directory" }
    }

    @After
    fun release() = runBlocking {
        // A failed test must retain ownership if Android still cannot confirm cleanup.
        val recovered = runners.map { runner ->
            runner.cancel()
            withTimeout(15_000) { runner.recover() }
        }.all { it }
        if (recovered) {
            if (::models.isInitialized) models.deleteRecursively()
            lease?.close()
        }
        assertTrue("Test worker cleanup could not be confirmed", recovered)
    }

    @Test
    fun consecutiveMissingWhisperModelsReturnErrorsAndReleaseTheWorker() = runBlocking {
        assumeTrue("This APK has no Whisper CPU runtime", WhisperEngine.isAvailable())
        val runner = runner()
        val errors = (1..2).map { index ->
            val model = candidate("missing-$index.bin", whisper = true)
            val result = run(runner, model)
            assertInitializationFailure(result)
            assertTrue("Missing model must be reported explicitly: ${result.error}",
                result.error.orEmpty().contains(model.file!!.absolutePath))
            assertTrue("The completed job must release ownership", runner.recover())
            result.error
        }
        assertEquals(2, errors.size)
    }

    @Test
    fun voskWorkerBootstrapsPrivateStorageBeforeRejectingAnInvalidModel() = runBlocking {
        assumeTrue("This APK has no Vosk CPU runtime", VoskEngine.isAvailable())
        val model = candidate("invalid-vosk", whisper = false)
        check(model.file!!.mkdir())
        val runner = runner()
        val result = run(runner, model)
        assertInitializationFailure(result)
        assertFalse("Worker omitted CommonJni context bootstrap: ${result.error}",
            result.error.orEmpty().contains("CommonJni.init", ignoreCase = true))
        assertTrue("The failed model must release ownership", runner.recover())
        // Rebind immediately after a native initialization failure. This exercises the
        // next Service instance even when Android retains its idle process in cache.
        assertInitializationFailure(run(runner, candidate("missing-after-vosk", whisper = false)))
    }

    @Test
    fun cancellingAnActuallyBoundServiceBeforeTheHandshakeAllowsANewJob() = runBlocking {
        assumeTrue("This APK has no Whisper CPU runtime", WhisperEngine.isAvailable())
        val delayed = DelayedConnectionContext(context)
        val runner = runner(delayed)
        val work = async(Dispatchers.Default) {
            run(runner, candidate("cancelled-before-handshake.bin", whisper = true))
        }
        try {
            val remote = withTimeout(30_000) { delayed.connected.await() }
            assertTrue("Android did not bind a live service", remote.isBinderAlive)
            assertEquals("The test must bind a remote Messenger, not an in-process fake",
                null, remote.queryLocalInterface("android.os.IMessenger"))
            runner.cancel()
            withTimeout(15_000) { work.cancelAndJoin() }
            assertEquals("Cancellation must unbind the actual service exactly once", 1, delayed.unbinds)
            assertTrue("An idle bound worker must not require app restart", runner.recover())
        } finally {
            runner.cancel()
            withTimeout(15_000) { work.cancelAndJoin() }
        }
        assertInitializationFailure(run(runner(), candidate("missing-after-cancel.bin", whisper = true)))
    }

    private fun runner(workerContext: Context = context) = BenchmarkIsolatedRunner(workerContext).also(runners::add)

    private fun candidate(name: String, whisper: Boolean) = BenchmarkCandidate(
        id = "process-test-$name", label = "Deliberately invalid test model",
        route = if (whisper) "Whisper / batch" else "Vosk / batch", sourceCodes = setOf("en"),
        file = File(models, name), acceleration = "cpu", artifactFingerprint = "a".repeat(64),
    )

    private suspend fun run(runner: BenchmarkIsolatedRunner, model: BenchmarkCandidate): BenchmarkResult =
        withTimeout(45_000) {
            runner.run(model, BenchmarkAudio.fromPcm16(ShortArray(16_000)), "", null,
                source = "en", target = "", measuredPasses = 3, attemptTimeoutSeconds = 20,
                jisrDialect = "ar", progress = {})
        }

    private fun assertInitializationFailure(result: BenchmarkResult) {
        assertNotNull("Invalid model was accepted as a successful benchmark", result.error)
        assertTrue("Initialization failure must have no inference measurements", result.computeMs.isEmpty())
        assertTrue("Initialization failure must have no transcription output", result.texts.isEmpty())
        assertEquals("Initialization failure must not be misreported as a cleanup failure", null, result.terminationReason)
    }

    /** Holds only the client callback; Android still creates and binds the actual service. */
    private class DelayedConnectionContext(base: Context) : ContextWrapper(base) {
        val connected = CompletableDeferred<IBinder>()
        var unbinds = 0
            private set
        private var client: ServiceConnection? = null
        private var remote: ServiceConnection? = null

        override fun getApplicationContext(): Context = this

        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            check(client == null) { "Expected a single real Android binding" }
            client = conn
            val adapter = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }
                override fun onServiceDisconnected(name: ComponentName) { conn.onServiceDisconnected(name) }
                override fun onBindingDied(name: ComponentName) { conn.onBindingDied(name) }
                override fun onNullBinding(name: ComponentName) { conn.onNullBinding(name) }
            }
            remote = adapter
            return baseContext.bindService(intent, adapter, flags)
        }

        override fun unbindService(conn: ServiceConnection) {
            check(conn === client) { "Unexpected benchmark binding" }
            baseContext.unbindService(checkNotNull(remote))
            unbinds++
        }
    }
}
