package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class CaptionTranslationBridgeTest {
    private class Fake : CancellableTextTranslator {
        override val id = "test"
        override val directions = setOf(TranslationDirection("ru", "ar"), TranslationDirection("fil", "ar"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var cancelled = false; var closed = false; var calls = 0
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            calls++; entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            return "translated:$text"
        }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }
    @Test fun queueIsBoundedAndAttachesToStableIds() = runBlocking {
        val fake = Fake(); val outputs = CopyOnWriteArrayList<Long>(); val errors = CopyOnWriteArrayList<String>()
        val complete = CompletableDeferred<Unit>()
        val bridge = CaptionTranslationBridge(this, "ar", { fake }, { id, _, _ -> outputs.add(id); if(outputs.size == 2) complete.complete(Unit) }, { it?.let(errors::add) }, capacity = 1)
        try {
            assertTrue(bridge.offer(10, "first", "ru-RU")); withTimeout(3000) { fake.entered.await() }
            assertTrue(bridge.offer(11, "second", "ru")); assertFalse(bridge.offer(12, "overflow", "ru"))
            assertTrue(errors.any { "queue is full" in it })
            fake.release.complete(Unit); withTimeout(3000) { complete.await() }
            assertEquals(listOf(10L, 11L), outputs.toList())
        } finally { bridge.close(); fake.release.complete(Unit); bridge.awaitClosed() }
        assertTrue(fake.closed)
    }
    @Test fun disablingCancelsInferenceAndDiscardsLateResult() = runBlocking {
        val fake = Fake(); val outputs = CopyOnWriteArrayList<Long>()
        val bridge = CaptionTranslationBridge(this, "ar", { fake }, { id, _, _ -> outputs.add(id) }, {})
        bridge.offer(1, "test", "ru"); withTimeout(3000) { fake.entered.await() }
        bridge.close(); assertTrue(fake.cancelled)
        fake.release.complete(Unit); bridge.awaitClosed()
        assertTrue(outputs.isEmpty()); assertTrue(fake.closed); assertFalse(bridge.offer(2, "late", "ru"))
    }
    @Test fun unknownAndUnsupportedSourcesAreNotGuessed() = runBlocking {
        val fake = Fake(); val errors = CopyOnWriteArrayList<String>(); val ready = CompletableDeferred<Unit>()
        val bridge = CaptionTranslationBridge(this, "ar", { fake }, { _, _, _ -> fail("Unexpected translation") }, { if(it != null) { errors.add(it); if(errors.size == 2) ready.complete(Unit) } })
        bridge.offer(1, "mixed", "mul"); bridge.offer(2, "unsupported", "ja")
        withTimeout(3000) { ready.await() }; bridge.close(); bridge.awaitClosed()
        assertEquals(0, fake.calls); assertTrue(errors.any { "unknown or mixed" in it }); assertTrue(errors.any { "ja → ar" in it })
    }
    @Test fun staleInferenceDoesNotPublish() = runBlocking {
        val fake = Fake(); var now = 0L; val noticed = CompletableDeferred<String>()
        val bridge = CaptionTranslationBridge(this, "ar", { fake }, { _, _, _ -> fail("Stale result") }, { if(it != null) noticed.complete(it) }, clock = { now }, maxAgeMs = 100)
        bridge.offer(1, "test", "ru"); withTimeout(3000) { fake.entered.await() }; now = 101
        fake.release.complete(Unit); assertTrue(withTimeout(3000) { noticed.await() }.contains("too late"))
        bridge.close(); bridge.awaitClosed()
    }
    @Test fun cancellationDuringLoadClosesModel() = runBlocking {
        val started = CompletableDeferred<Unit>(); val load = CompletableDeferred<Unit>(); val fake = Fake()
        val bridge = CaptionTranslationBridge(this, "ar", { started.complete(Unit); load.await(); fake }, { _, _, _ -> fail("Cancelled load") }, {})
        bridge.offer(1, "test", "ru"); withTimeout(3000) { started.await() }; bridge.close(); load.complete(Unit)
        bridge.awaitClosed(); assertTrue(fake.closed); assertEquals(0, fake.calls)
    }
    @Test fun startupDoesNotExpireTheFirstCaptionAndModelOpensOnlyOnce() = runBlocking {
        val loading = CompletableDeferred<Unit>(); val finishLoad = CompletableDeferred<Unit>()
        val fake = Fake(); fake.release.complete(Unit)
        var now = 0L; var opens = 0
        val output = CompletableDeferred<Long>()
        val bridge = CaptionTranslationBridge(this, "ar", {
            opens++; loading.complete(Unit); finishLoad.await(); fake
        }, { id, _, _ -> output.complete(id) }, {}, clock = { now }, maxAgeMs = 100)
        loading.await(); bridge.offer(9, "Русский текст", "ru-RU")
        now = 1000; finishLoad.complete(Unit)
        assertEquals(9L, withTimeout(3000) { output.await() })
        bridge.close(); bridge.awaitClosed(); assertEquals(1, opens)
    }
    @Test fun resetDiscardsInFlightAndQueuedCaptionsWithoutReloadingModel() = runBlocking {
        val fake = Fake(); val outputs = CopyOnWriteArrayList<Long>()
        val done = CompletableDeferred<Unit>(); var opens = 0
        val bridge = CaptionTranslationBridge(this, "ar", { opens++; fake }, { id, _, _ ->
            outputs.add(id); done.complete(Unit)
        }, {}, capacity = 1)
        try {
            bridge.offer(1, "old in flight", "ru")
            withTimeout(3000) { fake.entered.await() }
            assertTrue(bridge.hasPendingWork)
            assertTrue(bridge.offer(2, "old queued", "ru"))
            bridge.reset()
            assertFalse(bridge.hasPendingWork)
            assertFalse(fake.cancelled); assertFalse(fake.closed)
            assertTrue(bridge.offer(3, "new video", "ru"))
            assertTrue(bridge.hasPendingWork)
            fake.release.complete(Unit)
            withTimeout(3000) { done.await(); while (bridge.hasPendingWork) yield() }
            assertEquals(listOf(3L), outputs.toList())
            assertEquals(2, fake.calls); assertEquals(1, opens)
        } finally { bridge.close(); fake.release.complete(Unit); bridge.awaitClosed() }
    }

    @Test fun resetWhileLoadingKeepsOneLoadAndOnlyNewTimeline() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finishLoad = CompletableDeferred<Unit>()
        val fake = Fake().also { it.release.complete(Unit) }
        val outputs = CopyOnWriteArrayList<Long>(); val done = CompletableDeferred<Unit>()
        var opens = 0
        val bridge = CaptionTranslationBridge(this, "ar", {
            opens++; started.complete(Unit); finishLoad.await(); fake
        }, { id, _, _ -> outputs.add(id); done.complete(Unit) }, {})
        try {
            withTimeout(3000) { started.await() }
            bridge.offer(1, "old", "ru"); assertTrue(bridge.hasPendingWork)
            bridge.reset(); bridge.reset(); assertFalse(bridge.hasPendingWork)
            bridge.offer(2, "current", "ru"); finishLoad.complete(Unit)
            withTimeout(3000) { done.await(); while (bridge.hasPendingWork) yield() }
            assertEquals(listOf(2L), outputs.toList())
            assertEquals(1, opens); assertEquals(1, fake.calls)
        } finally { bridge.close(); finishLoad.complete(Unit); bridge.awaitClosed() }
    }

    @Test fun resetSuppressesOldInferenceErrorAndAllowsNewRequest() = runBlocking {
        val fake = Fake(); val errors = CopyOnWriteArrayList<String>()
        val done = CompletableDeferred<Unit>()
        val translator = object : CancellableTextTranslator by fake {
            override suspend fun translate(text: String, direction: TranslationDirection): String {
                if (text == "old") {
                    fake.entered.complete(Unit)
                    withContext(NonCancellable) { fake.release.await() }
                    error("old native failure")
                }
                return "new translation"
            }
        }
        val bridge = CaptionTranslationBridge(this, "ar", { translator }, { id, _, _ ->
            assertEquals(2L, id); done.complete(Unit)
        }, { it?.let(errors::add) })
        try {
            bridge.offer(1, "old", "ru"); withTimeout(3000) { fake.entered.await() }
            bridge.reset(); bridge.offer(2, "new", "ru"); fake.release.complete(Unit)
            withTimeout(3000) { done.await(); while (bridge.hasPendingWork) yield() }
            assertTrue(errors.isEmpty())
        } finally { bridge.close(); fake.release.complete(Unit); bridge.awaitClosed() }
    }

    @Test fun pendingWorkClearsAfterRejectedDirectionAndClose() = runBlocking {
        val fake = Fake(); val rejected = CompletableDeferred<Unit>()
        val bridge = CaptionTranslationBridge(this, "ar", { fake }, { _, _, _ -> fail("Unexpected translation") }, {
            if (it != null) rejected.complete(Unit)
        })
        bridge.offer(1, "unknown source", "mul")
        withTimeout(3000) { rejected.await(); while (bridge.hasPendingWork) yield() }
        bridge.offer(2, "accepted", "ru"); withTimeout(3000) { fake.entered.await() }
        assertTrue(bridge.hasPendingWork)
        bridge.close(); assertFalse(bridge.hasPendingWork)
        bridge.reset(); assertFalse(bridge.offer(3, "closed", "ru"))
        fake.release.complete(Unit); bridge.awaitClosed()
    }

    @Test fun closingOneBridgeDoesNotCancelOrPublishIntoAnother() = runBlocking {
        val first = Fake(); val second = Fake()
        val done = CompletableDeferred<String>()
        val retired = CaptionTranslationBridge(this, "ar", { first }, { _, _, _ -> fail("Retired result") }, {})
        val active = CaptionTranslationBridge(this, "ar", { second }, { _, text, _ -> done.complete(text) }, {})
        try {
            retired.offer(1, "old page", "ru"); active.offer(1, "new page", "ru")
            withTimeout(3000) { first.entered.await(); second.entered.await() }
            retired.close(); first.release.complete(Unit); retired.awaitClosed()
            assertTrue(first.closed); assertFalse(second.cancelled); assertFalse(second.closed)
            second.release.complete(Unit)
            assertEquals("translated:new page", withTimeout(3000) { done.await() })
        } finally {
            retired.close(); active.close(); first.release.complete(Unit); second.release.complete(Unit)
            retired.awaitClosed(); active.awaitClosed()
        }
    }

    @Test fun completedLineReportsPreparationQueueAndInferenceSeparately() = runBlocking {
        val now = AtomicLong(0)
        val releaseLoad = CompletableDeferred<Unit>()
        val result = CompletableDeferred<TranslationTiming>()
        val progress = CopyOnWriteArrayList<String>()
        val fake = Fake()
        val bridge = CaptionTranslationBridge(this, "ar", {
            releaseLoad.await(); fake
        }, { _, _, _ -> }, {}, clock = now::get, progress = progress::add,
            timing = { _, stages -> result.complete(stages) })
        try {
            bridge.offer(5, "hello", "ru")
            now.set(50); releaseLoad.complete(Unit)
            withTimeout(3000) { fake.entered.await() }
            now.set(65)
            fake.release.complete(Unit)
            val stages = withTimeout(3000) { result.await() }
            assertEquals(50L, stages.preparationMs)
            assertEquals(0L, stages.queueMs)
            assertEquals(15L, stages.inferenceMs)
            assertEquals(65L, stages.totalMs)
            withTimeout(3000) { while (bridge.hasPendingWork) yield() }
            assertTrue(progress.last().startsWith("Translating ru → ar"))
        } finally { bridge.close(); releaseLoad.complete(Unit); fake.release.complete(Unit); bridge.awaitClosed() }
    }

    @Test fun inferenceFailureRestoresReadyProgressAndKeepsRealError() = runBlocking {
        val progress = CopyOnWriteArrayList<String>()
        val error = CompletableDeferred<String>()
        val translator = object : CancellableTextTranslator {
            override val id = "broken"
            override val directions = setOf(TranslationDirection("zh", "en"))
            override suspend fun translate(text: String, direction: TranslationDirection): String =
                throw IllegalStateException("native decoder failed")
            override fun cancel() {}
            override fun close() {}
        }
        val bridge = CaptionTranslationBridge(this, "en", { translator }, { _, _, _ -> fail("No result expected") },
            { if (it != null) error.complete(it) }, progress = progress::add)
        try {
            assertTrue(bridge.offer(4, "中文", "zh"))
            assertEquals("native decoder failed", withTimeout(3000) { error.await() })
            withTimeout(3000) { while (bridge.hasPendingWork) yield() }
            assertEquals("Translator ready", progress.last())
        } finally { bridge.close(); bridge.awaitClosed() }
    }
}
