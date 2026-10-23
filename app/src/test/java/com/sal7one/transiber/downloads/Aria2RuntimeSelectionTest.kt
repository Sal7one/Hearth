package com.sal7one.transiber.downloads

import com.sal7one.hearth.core.downloads.Aria2ProbeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class Aria2RuntimeSelectionTest {
    @Test fun automaticCapabilityFailureFallsBackExplicitlyBeforeAnyTransfer() = runBlocking {
        val selection = selectAria2Runtime("auto") { throw Aria2ProbeException("aria2 --version exited 127: missing native symbol") }
        assertNull(selection.runtime)
        assertTrue(selection.notice.orEmpty().contains("missing native symbol"))
        assertTrue(selection.notice.orEmpty().contains("standard HTTPS"))
        assertTrue(selection.notice.orEmpty().contains("model verification"))
    }

    @Test fun explicitAriaSelectionRetainsActualCapabilityError() = runBlocking {
        val failure = Aria2ProbeException("aria2 does not advertise HTTPS")
        try { selectAria2Runtime("aria2") { throw failure }; fail("Explicit aria2 cannot silently select another backend") }
        catch (actual: Aria2ProbeException) { assertSame(failure, actual) }
    }

    @Test fun integrityCancellationAndTransportFailuresNeverBecomeAnAutomaticFallback() = runBlocking {
        for (failure in listOf(IllegalStateException("aria2 executable integrity check failed"),
            CancellationException("Owner paused download"), IOException("TLS certificate rejected"))) {
            try { selectAria2Runtime("auto") { throw failure }; fail("This error must be preserved") }
            catch (actual: Exception) { assertSame(failure, actual) }
        }
    }

    @Test fun manualHttpDoesNotStartOrInspectANativeExecutableAndSuccessfulAriaHasNoWarning() = runBlocking {
        val http = selectAria2Runtime("http") { error("The HTTP choice must not launch aria2") }
        assertNull(http.runtime); assertNull(http.notice)
        val runtime = Aria2Runtime(File("/private/aria2c"), mapOf("SSL_CERT_FILE" to "/private/trust.pem"))
        val aria = selectAria2Runtime("auto") { runtime }
        assertSame(runtime, aria.runtime); assertNull(aria.notice)
    }
}
