package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BenchmarkRunControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun batch() = BenchmarkBatch(listOf(BenchmarkCandidate("uninstalled-fixture", "Fixture", "Speech",
        setOf("en"))), "en", "") // No clip: validation fails before any native/runtime is opened.

    @After fun stopAndJoin() { runBlocking {
        BenchmarkRunController.stop()
        withTimeout(5_000) { BenchmarkRunController.state.first { !it.running } }
    } }

    @Test fun anotherLocalSessionIsNeverDisplacedByABenchmarkRequest() {
        val lease = LocalWorkGate.acquire("Captions")
        try {
            val failure = runCatching { BenchmarkRunController.start(context, listOf(batch())) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("Captions"))
            assertEquals("Captions", LocalWorkGate.owner.value)
            assertFalse(BenchmarkRunController.state.value.running)
        } finally { lease.close() }
    }

    @Test fun staleNotificationCannotCancelANewerRun() {
        BenchmarkRunController.start(context, listOf(batch()))
        val old = BenchmarkRunController.state.value.runId!!
        BenchmarkRunController.stop(old)
        assertFalse(BenchmarkRunController.execute(old))
        BenchmarkRunController.start(context, listOf(batch()))
        val current = BenchmarkRunController.state.value.runId
        BenchmarkRunController.stop(old)
        assertNotEquals(old, current)
        assertTrue(BenchmarkRunController.state.value.running)
        assertEquals(current, BenchmarkRunController.state.value.runId)
    }

    @Test fun immediateCancelCannotLeaveAStartingJobOrLeaseStuck() = runBlocking {
        repeat(25) {
            BenchmarkRunController.start(context, listOf(batch()))
            val id = BenchmarkRunController.state.value.runId!!
            assertTrue(BenchmarkRunController.execute(id))
            BenchmarkRunController.stop(id)
            withTimeout(5_000) { BenchmarkRunController.state.first { !it.running } }
            assertNull(LocalWorkGate.owner.value)
        }
    }

    @Test fun serviceStartupFailureRemainsVisibleAndAnotherRunCanStart() {
        BenchmarkRunController.start(context, listOf(batch()))
        BenchmarkRunController.failStart(BenchmarkRunController.state.value.runId!!, "Foreground start rejected")
        assertFalse(BenchmarkRunController.state.value.running)
        assertEquals("Foreground start rejected", BenchmarkRunController.state.value.error)
        BenchmarkRunController.start(context, listOf(batch()))
        assertTrue(BenchmarkRunController.state.value.running)
        assertNull(BenchmarkRunController.state.value.error)
    }
}
