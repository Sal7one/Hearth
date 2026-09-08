package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
}
