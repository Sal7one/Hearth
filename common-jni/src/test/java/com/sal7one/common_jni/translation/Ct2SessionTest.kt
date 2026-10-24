package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.hearth.core.TranslationContentLimitException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Test

class Ct2SessionTest {
    private class Driver : Ct2Runtime {
        var protocol=2;var created=0;var destroyed=0;var cancelled=0;var output="translated"
        var target="";var finish: (() -> Unit)?=null;var failure: Exception?=null
        val inputs=mutableListOf<String>();val targets=mutableListOf<String>()
        var translatePart: ((String) -> String)?=null
        override fun protocolVersion()=protocol
        override fun create(path: ByteArray,mode: Int): Long { created++;return 42 }
        override fun translate(handle: Long,text: ByteArray,target: ByteArray): ByteArray {
            this.target=target.toString(Charsets.UTF_8);targets+=this.target
            val source=text.toString(Charsets.UTF_8);inputs+=source
            failure?.let { throw it }
            val result=translatePart?.invoke(source) ?: output
            finish?.invoke();return result.toByteArray()
        }
        override fun cancel(handle: Long) { cancelled++ }
        override fun destroy(handle: Long) { destroyed++ }
    }
    private fun fixture(block: (java.io.File,Ct2ModelSpec) -> Unit) {
        val root=Files.createTempDirectory("ct2-session").toFile()
        try {
            val data="model bytes".toByteArray();java.io.File(root,"model.bin").writeBytes(data)
            val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            val spec=Ct2Catalog.find("ct2-small100-int8")!!.copy(files=listOf(Ct2File("model.bin",data.size.toLong(),hash,"publisher/test","0".repeat(40))))
            block(root,spec)
        } finally { root.deleteRecursively() }
    }
    @Test fun emptyOutputAndActualRuntimeDeadlineErrorsAreNotSuccessfulTranslations() = fixture { root,spec ->
        val driver=Driver();val session=Ct2TranslationSession.openWithRuntime(root,spec,driver)
        try {
            driver.output=" "
            assertThrows(IllegalStateException::class.java) { runBlocking { session.translate("hello",TranslationDirection("en","ar")) } }
            driver.failure=IllegalStateException("CT2 translation exceeded 20 seconds")
            val error=assertThrows(IllegalStateException::class.java) { runBlocking { session.translate("hello",TranslationDirection("en","ar")) } }
            assertEquals("CT2 translation exceeded 20 seconds",error.message)
        } finally { session.close() }
        assertEquals(1,driver.destroyed)
    }
    @Test fun cancelledLateNativeResultIsSuppressedAndCloseRetiresExactlyOnce() = fixture { root,spec ->
        val driver=Driver();val session=Ct2TranslationSession.openWithRuntime(root,spec,driver)
        driver.finish={session.cancel()}
        assertThrows(IllegalStateException::class.java) { runBlocking { session.translate("hello",TranslationDirection("en","ar")) } }
        assertEquals(1,driver.cancelled)
        session.close();session.close();session.cancel();assertEquals(1,driver.destroyed)
    }
    @Test fun nativeLanguageAliasesAndProtocolAreValidatedWithoutLoadingAStaleRuntime() = fixture { root,spec ->
        val driver=Driver()
        Ct2TranslationSession.openWithRuntime(root,spec,driver).use { session ->
            runBlocking { session.translate("hello",TranslationDirection("en","fil")) }
            assertEquals("tl",driver.target)
        }
        val old=Driver().apply { protocol=0 }
        assertThrows(IllegalStateException::class.java) { Ct2TranslationSession.openWithRuntime(root,spec,old) }
        assertEquals(0,old.created)
    }
    @Test fun jisrDialectIsFrozenPerSessionAndCannotChangeTranslationDirection() = fixture { root,fixture ->
        for (mode in listOf(2,4)) {
            val spec=fixture.copy(tokenizerMode=mode,sources=setOf("en"),targets=setOf("ar"))
            val driver=Driver()
            Ct2TranslationSession.openWithRuntime(root,spec,driver,"ars").use { session ->
                runBlocking { session.translate("Hello",TranslationDirection("en","ar")) }
                assertEquals("ars",driver.target)
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { session.translate("مرحبا",TranslationDirection("ar","en")) }
                }
            }
            assertEquals(1,driver.destroyed)
            assertThrows(IllegalArgumentException::class.java) { Ct2TranslationSession.openWithRuntime(root,spec,Driver(),"ar-SA") }
        }
        val old=Driver().apply { protocol=1 }
        assertThrows(IllegalStateException::class.java) { Ct2TranslationSession.openWithRuntime(root,fixture,old) }
        assertEquals(0,old.created)
    }

    @Test fun articleRequestUsesBoundedPartsWithoutLosingSourceOrChangingTargetAlias() = fixture { root,spec ->
        val driver=Driver()
        val source="Coffee brewing needs fresh water. ".repeat(75).trim()
        Ct2TranslationSession.openWithRuntime(root,spec,driver).use { session ->
            val output=runBlocking { session.translate(source,TranslationDirection("en","fil")) }
            assertTrue(driver.inputs.size>1)
            assertTrue(driver.inputs.all { it.length<=320 })
            assertEquals(source,driver.inputs.joinToString(""))
            assertTrue(driver.targets.all { it=="tl" })
            assertEquals(List(driver.inputs.size) { "translated" }.joinToString(" "),output)
        }
        assertEquals(1,driver.destroyed)
    }

    @Test fun contentLimitRetriesKeepJisrDialectAndPublishOnlyAfterAllPartsComplete() = fixture { root,fixture ->
        val spec=fixture.copy(tokenizerMode=2,sources=setOf("en"),targets=setOf("ar"))
        val driver=Driver()
        val completed=mutableListOf<String>()
        driver.translatePart={ source ->
            if(source.length>80) throw RuntimeException("CT2 translation exceeded its output token limit; incomplete text was not published")
            completed+=source
            "مكتمل"
        }
        Ct2TranslationSession.openWithRuntime(root,spec,driver,"ars").use { session ->
            val source="x".repeat(600)
            val output=runBlocking { session.translate(source,TranslationDirection("en","ar")) }
            assertEquals(source,completed.joinToString(""))
            assertTrue(driver.targets.all { it=="ars" })
            assertEquals(List(completed.size) { "مكتمل" }.joinToString(" "),output)
        }
        assertEquals(1,driver.destroyed)
    }

    @Test fun unrecoverableContentLimitRetainsActualNativeError() = fixture { root,spec ->
        val native=RuntimeException("CT2 translation exceeded its output token limit; incomplete text was not published")
        val driver=Driver().apply { failure=native }
        Ct2TranslationSession.openWithRuntime(root,spec,driver).use { session ->
            val error=assertThrows(TranslationContentLimitException::class.java) {
                runBlocking { session.translate("small source",TranslationDirection("en","ar")) }
            }
            assertEquals(
                "CT2 could not translate this text within its content limits; choose a smaller text region",
                error.message,
            )
            // withContext may recover the coroutine stack by copying the typed exception
            // and linking the original as its cause. The actual native object must survive.
            val causes=generateSequence(error.cause) { it.cause }.take(8).toList()
            assertSame(native,causes.lastOrNull())
            assertEquals(1,driver.inputs.size)
        }
    }

    @Test fun coroutineCancellationAfterNativeCallSuppressesResultAndStopsRemainingParts() = fixture { root,spec ->
        val driver=Driver()
        Ct2TranslationSession.openWithRuntime(root,spec,driver).use { session ->
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val job=currentCoroutineContext()[Job]!!
                    driver.finish={ job.cancel(CancellationException("screen request replaced")) }
                    session.translate("x".repeat(600),TranslationDirection("en","ar"))
                }
            }
            assertEquals(1,driver.inputs.size)
            assertEquals(0,driver.cancelled)
        }
        assertEquals(1,driver.destroyed)
    }
}
