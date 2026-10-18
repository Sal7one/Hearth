package com.sal7one.transiber.preferences

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StartupAuditJobTest {
    @Test fun activityAndServiceEntriesShareOneAuditAndSuccessfulCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
            var reads = 0; var scans = 0
            val job = StartupAuditJob(scope, { reads++; started.complete(Unit); release.await() }, { scans++ })
            val application = job.start(); started.await()
            repeat(30) { assertSame(application, job.start(retry = true)) }
            release.complete(Unit)
            assertFalse(application.await().failed)
            assertSame(application, job.start(retry = true))
            assertEquals(1, reads); assertEquals(1, scans)
        } finally { scope.cancel() }
    }
    @Test fun failedPreferencesRetainErrorSkipModelsAndRetryOnlyWhenRequested() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var fail = true; var reads = 0; var scans = 0
            val job = StartupAuditJob(scope, { reads++; if (fail) throw IOException("settings unavailable") }, { scans++ })
            val failed = job.start(); val result = failed.await()
            assertTrue(result.preferencesError.orEmpty().contains("settings unavailable"))
            assertNull(result.modelError); assertEquals(0, scans)
            fail = false
            assertSame(failed, job.start())
            val retried = job.start(retry = true)
            assertNotSame(failed, retried); assertFalse(retried.await().failed)
            assertEquals(2, reads); assertEquals(1, scans)
        } finally { scope.cancel() }
    }
    @Test fun modelAuditFailureDoesNotBecomeASettingsFailureAndCanBeRechecked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var fail = true
            val job = StartupAuditJob(scope, {}, { if (fail) throw IOException("model folder inaccessible") })
            val first = job.start().await()
            assertNull(first.preferencesError)
            assertTrue(first.modelError.orEmpty().contains("model folder inaccessible"))
            fail = false
            assertFalse(job.start(retry = true).await().failed)
        } finally { scope.cancel() }
    }
}
