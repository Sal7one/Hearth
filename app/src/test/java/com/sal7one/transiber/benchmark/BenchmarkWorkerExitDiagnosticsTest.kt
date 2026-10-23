package com.sal7one.transiber.benchmark

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class BenchmarkWorkerExitDiagnosticsTest {
    @Test fun nativeCrashesAndLowMemoryHaveDifferentExplanations() {
        assertTrue(BenchmarkWorkerExitDiagnostics.format(ApplicationExitInfo.REASON_CRASH_NATIVE, 11, "SIGSEGV")
            .contains("native runtime crashed: SIGSEGV"))
        assertTrue(BenchmarkWorkerExitDiagnostics.format(ApplicationExitInfo.REASON_LOW_MEMORY, 9, null)
            .contains("memory was low"))
    }

    @Test fun ambiguousKillSignalDoesNotClaimLowMemoryAndNormalExitIsNotCalledACrash() {
        val signaled = BenchmarkWorkerExitDiagnostics.format(ApplicationExitInfo.REASON_SIGNALED, 9, null)
        assertTrue(signaled.contains("signal 9")); assertFalse(signaled.contains("memory"))
        val normal = BenchmarkWorkerExitDiagnostics.format(ApplicationExitInfo.REASON_EXIT_SELF, 0, null)
        assertTrue(normal.contains("status 0")); assertFalse(normal.contains("crashed"))
    }

    @Test fun vendorExitDescriptionsAreBounded() {
        assertTrue(BenchmarkWorkerExitDiagnostics.format(999, 0, "x".repeat(10_000)).length < 650)
    }
}
