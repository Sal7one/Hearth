package com.sal7one.transiber.translation
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class TypedTranslationControllerTest {
    private class Engine: CancellableTextTranslator {
        override val id="test"
        override val directions=setOf(TranslationDirection("en","ar"))
        val inputs=mutableListOf<String>();var closed=0;var cancelled=0
        val pending=mutableListOf<CompletableDeferred<String>>()
        override suspend fun translate(text:String,direction:TranslationDirection):String {inputs+=text;return CompletableDeferred<String>().also {pending+=it}.await()}
        override fun cancel(){cancelled++}
        override fun close(){closed++}
    }
    @Test fun debouncesCoalescesAndNeverPublishesAnOldRevision()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val engine=Engine();var released=0
        val c=TypedTranslationController({engine},{AutoCloseable {released++}},dispatcher,dispatcher)
        val route=ConversationTranslatorSnapshot("test")
        c.update("a","en","ar",route);advanceTimeBy(250);c.update("ab","en","ar",route);advanceTimeBy(500);runCurrent()
        assertEquals(listOf("ab"),engine.inputs)
        c.update("abc","en","ar",route,true);runCurrent();c.update("latest","en","ar",route,true);runCurrent()
        engine.pending[0].complete("obsolete");runCurrent()
        assertEquals("",c.state.value.output);assertEquals(listOf("ab","latest"),engine.inputs)
        engine.pending[1].complete("newest");runCurrent();assertEquals("newest",c.state.value.output)
        c.pause();runCurrent();assertEquals(1,engine.closed);assertEquals(1,released);assertEquals("",c.state.value.output)
        c.close();runCurrent()
    }
    @Test fun backgroundInvalidatesInFlightAndClosesBeforeNextLoad()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val engines=mutableListOf<Engine>();var held=0
        val c=TypedTranslationController({Engine().also {engines+=it}},{held++;AutoCloseable {held--}},dispatcher,dispatcher)
        val route=ConversationTranslatorSnapshot("test")
        c.update("old","en","ar",route,true);runCurrent();c.pause();runCurrent()
        c.update("resumed","en","ar",route,true);runCurrent()
        engines[0].pending[0].complete("stale");runCurrent()
        assertEquals(1,engines[0].closed);assertEquals(2,engines.size);assertEquals(1,held);assertEquals("",c.state.value.output)
        engines[1].pending[0].completeExceptionally(IllegalStateException("actual failure"));runCurrent()
        assertEquals("actual failure",c.state.value.error);assertEquals(0,held)
        c.close();runCurrent()
    }
}
