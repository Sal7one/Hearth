package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Ct2SessionTest {
    private class Driver : Ct2Runtime {
        var protocol=1;var created=0;var destroyed=0;var cancelled=0;var output="translated"
        var target="";var finish: (() -> Unit)?=null;var failure: Exception?=null
        override fun protocolVersion()=protocol
        override fun create(path: ByteArray,mode: Int): Long { created++;return 42 }
        override fun translate(handle: Long,text: ByteArray,target: ByteArray): ByteArray {
            this.target=target.toString(Charsets.UTF_8);failure?.let { throw it };finish?.invoke();return output.toByteArray()
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
}
