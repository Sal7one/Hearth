package com.sal7one.hearth.desktop.media

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assume.assumeTrue
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** No test opens ScreenCaptureKit capture, a microphone, or any live audio source. */
class DesktopSystemAudioRecorderTest {
    @Test fun finishDrainsOwnedPcmAndJoinsTheHelper() = runBlocking<Unit> {
        val pipe = PipedInputStream(8192); val writer = PipedOutputStream(pipe)
        val expected = ByteArray(3200) { (it % 127).toByte() }
        writer.write(magic + record(expected))
        lateinit var child: FakeProcess
        child = FakeProcess(pipe, alive = true, onStop = { writer.write(end); writer.close(); child.terminate() })
        val capture = DesktopSystemAudioRecorder({ child }, Unit)
        val received = CompletableDeferred<Unit>()
        val frames = mutableListOf<ByteArray>()
        val collection = async { capture.start().collect { frames += it; received.complete(Unit) } }
        withTimeout(3000) { received.await(); capture.finish(); collection.await() }
        assertEquals(1, frames.size); assertContentEquals(expected, frames.single())
        assertFalse(child.isAlive); assertTrue(child.stopSent)
        assertFailsWith<IllegalStateException> { capture.start().toList() }
    }

    @Test fun cancelledSlowConsumerReleasesRendezvousBeforeJoiningProducer() = runBlocking<Unit> {
        val bytes = CountingInput(magic + ByteArrayOutputStream().apply { repeat(100) { write(record(ByteArray(3200))) }; write(end) }.toByteArray())
        val child = FakeProcess(bytes, alive = true)
        val capture = DesktopSystemAudioRecorder({ child }, Unit)
        val received = CompletableDeferred<Unit>()
        val collection = launch { capture.start().collect { received.complete(Unit); awaitCancellation() } }
        withTimeout(3000) {
            received.await()
            while (bytes.readBytes.get() < 6408) delay(5)
            collection.cancelAndJoin() // This is the historical full-channel/join deadlock shape.
        }
        assertTrue(bytes.readBytes.get() <= 4 + 3 * 3204, "Unexpected implicit prefetch: ${bytes.readBytes.get()} bytes")
        assertTrue(child.destroyed); assertFalse(child.isAlive)
    }

    @Test fun actualNativePermissionFailureIsPreserved() = runBlocking<Unit> {
        val child = FakeProcess(ByteArrayInputStream(byteArrayOf()), alive = false, code = 1,
            diagnostic = "Allow Hearth in Screen & System Audio Recording")
        val capture = DesktopSystemAudioRecorder({ child }, Unit)
        val error = withTimeout(3000) { assertFailsWith<IllegalStateException> { capture.start().toList() } }
        assertContains(error.message.orEmpty(), "Screen & System Audio Recording")
    }

    @Test fun brokenControlCloseStillJoinsAndCompletesFinishWithTheActualFailure() = runBlocking<Unit> {
        supervisorScope {
            val pipe = PipedInputStream(8192); val writer = PipedOutputStream(pipe)
            writer.write(magic + record(ByteArray(3200)))
            lateinit var child: FakeProcess
            child = FakeProcess(pipe, alive = true, failControlClose = true,
                onStop = { writer.write(end); writer.close(); child.terminate() })
            val capture = DesktopSystemAudioRecorder({ child }, Unit)
            val received = CompletableDeferred<Unit>()
            val collection = async { capture.start().collect { received.complete(Unit) } }
            withTimeout(3000) { received.await() }
            val failure = assertFailsWith<IOException> { withTimeout(3000) { capture.finish() } }
            assertEquals("Broken control close", failure.message)
            assertFailsWith<IOException> { withTimeout(3000) { collection.await() } }
            assertFalse(child.isAlive)
        }
    }

    @Test fun finishBeforeCollectionNeverLaunchesACaptureProcess() = runBlocking<Unit> {
        var launches = 0
        val capture = DesktopSystemAudioRecorder({ launches++; error("Must not launch") }, Unit)
        capture.finish()
        assertTrue(capture.start().toList().isEmpty()); assertEquals(0, launches)
    }

    @Test fun cancelBeforeCollectionNeverLaunchesACaptureProcess() = runBlocking<Unit> {
        var launches = 0
        val capture = DesktopSystemAudioRecorder({ launches++; error("Must not launch") }, Unit)
        capture.cancel()
        assertFailsWith<CancellationException> { capture.start().toList() }
        assertEquals(0, launches)
    }

    @Test fun protocolRejectsOversizedOddTruncatedAndMissingFinalRecords() {
        for (length in listOf(1, 3202, -1)) assertFailsWith<IllegalStateException> { readSystemAudioFrame(ByteArrayInputStream(size(length))) }
        assertFailsWith<IllegalStateException> { readSystemAudioFrame(ByteArrayInputStream(size(4) + byteArrayOf(1, 2))) }
        assertFailsWith<IllegalStateException> { readSystemAudioFrame(ByteArrayInputStream(byteArrayOf())) }
        assertNull(readSystemAudioFrame(ByteArrayInputStream(end)))
    }

    @Test fun stagedProbeLoadsHelperWithoutRequestingPermissionOrCapturingAudio() = runBlocking<Unit> {
        if (!System.getProperty("os.name").startsWith("Mac", true)) return@runBlocking
        if (System.getProperty("hearth.media.required") == "true") assertTrue(DesktopSystemAudioRecorder.available, DesktopSystemAudioRecorder.availabilityReason)
        assumeTrue(DesktopSystemAudioRecorder.available)
        val directory = Files.createTempDirectory("hearth-system-audio-probe-")
        try {
            val report = DesktopSystemAudioRecorder.probe(directory)
            assertContains(report, "PERMISSION=not-requested")
            assertContains(report, "HEARTH_SYSTEM_AUDIO_PROTOCOL=1")
        } finally { directory.toFile().deleteRecursively() }
    }

    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val readBytes = AtomicInteger()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it > 0) readBytes.addAndGet(it) }
    }
    private class FakeProcess(private val audio: InputStream, alive: Boolean, private val code: Int = 0,
        diagnostic: String = "", private val failControlClose: Boolean = false, private val onStop: () -> Unit = {}) : Process() {
        private val running = AtomicBoolean(alive)
        private val exited = CountDownLatch(if (alive) 1 else 0)
        private val errors = ByteArrayInputStream(diagnostic.toByteArray())
        @Volatile var destroyed = false
        @Volatile var stopSent = false
        private val control = object : ByteArrayOutputStream() {
            override fun flush() {
                if (!stopSent && toString(Charsets.US_ASCII).endsWith("STOP\n")) { stopSent = true; onStop() }
            }
            override fun close() { if (failControlClose) throw IOException("Broken control close") }
        }
        fun terminate() { running.set(false); exited.countDown() }
        override fun getInputStream() = audio
        override fun getErrorStream(): InputStream = errors
        override fun getOutputStream(): OutputStream = control
        override fun isAlive() = running.get()
        override fun waitFor(): Int { exited.await(); return code }
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return code }
        override fun destroy() { destroyed = true; terminate(); audio.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    companion object {
        private val magic = byteArrayOf(72, 83, 65, 49)
        private val end = ByteArray(4)
        private fun size(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        private fun record(bytes: ByteArray) = size(bytes.size) + bytes
    }
}
