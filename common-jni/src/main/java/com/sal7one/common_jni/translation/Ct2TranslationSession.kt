package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.hearth.core.Ct2TextTranslation
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object Ct2Native {
    init { System.loadLibrary("transiber_ct2") }
    external fun protocolVersion(): Int
    external fun create(path: ByteArray, mode: Int): Long
    external fun translate(handle: Long, text: ByteArray, target: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
internal interface Ct2Runtime {
    fun protocolVersion(): Int
    fun create(path: ByteArray,mode: Int): Long
    fun translate(handle: Long,text: ByteArray,target: ByteArray): ByteArray
    fun cancel(handle: Long)
    fun destroy(handle: Long)
}
private object JniCt2Runtime : Ct2Runtime {
    override fun protocolVersion()=Ct2Native.protocolVersion()
    override fun create(path: ByteArray,mode: Int)=Ct2Native.create(path,mode)
    override fun translate(handle: Long,text: ByteArray,target: ByteArray)=Ct2Native.translate(handle,text,target)
    override fun cancel(handle: Long)=Ct2Native.cancel(handle)
    override fun destroy(handle: Long)=Ct2Native.destroy(handle)
}
class Ct2TranslationSession private constructor(private val spec: Ct2ModelSpec, handle: Long,private val runtime: Ct2Runtime, private val arabicDialect: String) : CancellableTextTranslator {
    private val handle = AtomicLong(handle)
    private val cancelled = AtomicBoolean(false)
    override val id get() = spec.id
    override val directions get() = spec.directions
    override val executionInfo get() = "CPU · CTranslate2 4.8.3 · Ruy · ${if(spec.tokenizerMode in 1..3) "INT8_FLOAT32" else "FLOAT32"} · 2 threads · greedy" + if (spec.tokenizerMode==2 || spec.tokenizerMode==4) " · $arabicDialect" else ""
    override suspend fun translate(text: String, direction: TranslationDirection): String = withContext(Dispatchers.IO) {
        require(spec.supports(direction.source,direction.target)) { "${spec.label} does not support ${direction.source} → ${direction.target}" }
        val current=handle.get();check(current!=0L) { "CT2 session is closed" }
        val context=currentCoroutineContext()
        fun ensureRunning() {
            context.ensureActive()
            check(!cancelled.get() && handle.get()==current) { "CT2 translation cancelled or closed" }
        }
        val normalized=TranslationLanguages.normalize(direction.target)
        val target=when { spec.tokenizerMode==2 || spec.tokenizerMode==4 -> arabicDialect; spec.tokenizerMode==1 && normalized=="fil" -> "tl"; else -> normalized }
        val targetBytes=target.toByteArray(Charsets.UTF_8)
        Ct2TextTranslation.translate(text,::ensureRunning) { part ->
            runtime.translate(current,part.toByteArray(Charsets.UTF_8),targetBytes).toString(Charsets.UTF_8)
        }
    }
    override fun cancel() { cancelled.set(true);handle.get().takeIf { it!=0L }?.let(runtime::cancel) }
    override fun close() { cancelled.set(true);handle.getAndSet(0).takeIf { it!=0L }?.let(runtime::destroy) }
    companion object {
        fun open(directory: File,spec: Ct2ModelSpec, arabicDialect: String = "ar"): Ct2TranslationSession = openWithRuntime(directory,spec,JniCt2Runtime,arabicDialect)
        internal fun openWithRuntime(directory: File,spec: Ct2ModelSpec,runtime: Ct2Runtime, arabicDialect: String = "ar"): Ct2TranslationSession {
            JisrDialects.requireSupported(arabicDialect)
            val protocol=try { runtime.protocolVersion() } catch(e: LinkageError) { throw IllegalStateException("CT2 runtime is unavailable for this APK architecture: ${e.message}",e) }
            check(protocol==2) { "CT2 native runtime protocol mismatch; update the app runtime" }
            spec.verify(directory)
            val handle=runtime.create(directory.absolutePath.toByteArray(Charsets.UTF_8),spec.tokenizerMode)
            check(handle!=0L) { "CT2 returned an invalid handle" }
            return Ct2TranslationSession(spec,handle,runtime,arabicDialect)
        }
    }
}
