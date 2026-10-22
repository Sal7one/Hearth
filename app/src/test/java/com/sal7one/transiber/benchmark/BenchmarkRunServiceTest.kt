package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BenchmarkRunServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var serviceController: ServiceController<BenchmarkRunService>? = null
    private fun service() = Robolectric.buildService(BenchmarkRunService::class.java).create().also { serviceController = it }.get()
    private fun command(id: String, action: String = BenchmarkRunService.ACTION_START) =
        Intent(context, BenchmarkRunService::class.java).setAction(action).putExtra(BenchmarkRunService.EXTRA_RUN, id)
    private fun batch(waitForMain: Boolean = false) = BenchmarkBatch(
        listOf(BenchmarkCandidate("uninstalled-fixture", "Fixture", "Speech", setOf("en"))), "en", "",
        clip = if (waitForMain) BenchmarkAudio.fromPcm16(ShortArray(8_000)) else null)
    private fun begin(waitForMain: Boolean = false): String {
        BenchmarkRunController.start(context, listOf(batch(waitForMain)))
        return checkNotNull(BenchmarkRunController.state.value.runId)
    }
    private fun awaitStoppedWithoutAdvancingServiceObserver() = runBlocking {
        withTimeout(5_000) { BenchmarkRunController.state.first { !it.running } }
    }
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            Thread.sleep(5)
        }
        assertTrue("Expected the service/controller to finish", done())
    }

    @After fun cleanup() {
        BenchmarkRunController.stop()
        pumpUntil { !BenchmarkRunController.state.value.running }
        serviceController?.destroy()
        assertNull(LocalWorkGate.owner.value)
    }

    @Test fun nextRunStartsBeforeThePreviousSampledObserverHasFinished() {
        val service = service()
        val first = begin()
        service.onStartCommand(command(first), 0, 1)
        val firstWakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(firstWakeLock.isHeld)
        awaitStoppedWithoutAdvancingServiceObserver()
        // The previous service is still alive: its 750 ms main-loop sampler has not run.
        val second = begin()
        service.onStartCommand(command(second), 0, 2)
        assertFalse(firstWakeLock.isHeld)
        assertNotSame(firstWakeLock, ShadowPowerManager.getLatestWakeLock())
        awaitStoppedWithoutAdvancingServiceObserver()
        assertEquals(second, BenchmarkRunController.state.value.runId)
        assertTrue(BenchmarkRunController.state.value.error.orEmpty().contains("WAV"))
        pumpUntil { !ShadowPowerManager.getLatestWakeLock().isHeld }
        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test fun duplicateStartFinishesTheLatestAndroidStartIdAndReleasesTheWakeLock() {
        val service = service()
        val id = begin(waitForMain = true)
        service.onStartCommand(command(id), 0, 1)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(BenchmarkRunController.state.value.running)
        service.onStartCommand(command(id), 0, 2)
        assertSame(wakeLock, ShadowPowerManager.getLatestWakeLock())
        assertFalse(shadowOf(service).isStoppedBySelf)
        pumpUntil { !BenchmarkRunController.state.value.running && !wakeLock.isHeld }
        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test fun staleStartAndStopCannotTearDownCurrentOwnerAndDestroyCancelsIt() {
        val service = service()
        val id = begin(waitForMain = true)
        service.onStartCommand(command(id), 0, 1)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        service.onStartCommand(command("old-run"), 0, 2)
        service.onStartCommand(command("old-run", "com.sal7one.transiber.benchmark.STOP"), 0, 3)
        assertEquals(id, BenchmarkRunController.state.value.runId)
        assertTrue(BenchmarkRunController.state.value.running)
        assertTrue(wakeLock.isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        serviceController!!.destroy(); serviceController = null
        assertFalse(wakeLock.isHeld)
        pumpUntil { !BenchmarkRunController.state.value.running }
    }
}
