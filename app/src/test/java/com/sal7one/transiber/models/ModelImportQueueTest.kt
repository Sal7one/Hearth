package com.sal7one.transiber.models

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ModelImportQueueTest {
    @Test fun invalidSourceDoesNotStopLaterFilesInTheChosenBatch() = runBlocking {
        val owner = "test-${UUID.randomUUID()}"
        val uris = listOf("first", "bad", "last").map { Uri.parse("content://imports/$it") }
        ModelImportQueue.submit(RuntimeEnvironment.getApplication(), owner, uris) { source ->
            check(source.uri.lastPathSegment != "bad") { "Publisher hash mismatch" }
            source.uri.lastPathSegment.orEmpty()
        }
        val result = withTimeout(5000) { ModelImportQueue.state.first { it[owner]?.active == false }.getValue(owner) }
        assertEquals(3, result.outcomes.size)
        assertEquals(listOf("first", "last"), result.outcomes.filter { it.error.isBlank() }.map { it.result })
        assertTrue(result.outcomes.single { it.error.isNotBlank() }.error.contains("Publisher hash mismatch"))
    }
    @Test fun leavingTheCallingScopeDoesNotCancelApplicationOwnedImports() = runBlocking {
        val owner = "test-${UUID.randomUUID()}"
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            caller.launch {
                ModelImportQueue.submit(RuntimeEnvironment.getApplication(), owner, listOf(Uri.parse("content://imports/model"))) {
                    started.complete(Unit); finish.await(); "verified"
                }
            }.join()
            withTimeout(5000) { started.await() }
            caller.cancel()
            finish.complete(Unit)
            val result = withTimeout(5000) { ModelImportQueue.state.first { it[owner]?.active == false }.getValue(owner) }
            assertEquals("verified", result.outcomes.single().result)
            assertFalse(result.cancelled)
        } finally { caller.cancel(); ModelImportQueue.cancel(owner) }
    }
    @Test fun stoppingTheBatchPreventsRemainingSourcesAndReleasesTheImportLane() = runBlocking {
        val owner = "test-${UUID.randomUUID()}"
        val started = CompletableDeferred<Unit>()
        var opened = 0
        ModelImportQueue.submit(RuntimeEnvironment.getApplication(), owner,
            listOf(Uri.parse("content://imports/a"), Uri.parse("content://imports/b"))) {
            opened++; started.complete(Unit); awaitCancellation()
        }
        withTimeout(5000) { started.await() }
        ModelImportQueue.cancel(owner)
        val result = withTimeout(5000) { ModelImportQueue.state.first { it[owner]?.active == false }.getValue(owner) }
        assertTrue(result.cancelled)
        assertEquals(1, opened)
        val next = "test-${UUID.randomUUID()}"
        ModelImportQueue.submit(RuntimeEnvironment.getApplication(), next, listOf(Uri.parse("content://imports/c"))) { "ready" }
        val after = withTimeout(5000) { ModelImportQueue.state.first { it[next]?.active == false }.getValue(next) }
        assertEquals("ready", after.outcomes.single().result)
    }
}
