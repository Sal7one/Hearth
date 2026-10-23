package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.ocr.ComicNative
import com.sal7one.common_jni.ocr.JapaneseNative
import com.sal7one.hearth.core.vision.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real CPU ORT adapters using the same model pins, geometry and page processing as Android. */
class DesktopMangaOcr(directory: Path) : AutoCloseable, PageOcrReader {
    private val closed=AtomicBoolean(false)
    private val page: MangaPageOcr
    init {
        for (asset in MangaArtifacts.files) {
            val file=directory.resolve("${asset.id}.onnx")
            check(Files.isRegularFile(file) && Files.size(file)==asset.bytes && sha(file)==asset.sha256) { "Manga model checksum/size failed: ${asset.id}. Repair or import the pinned bundle." }
        }
        val detector=DesktopComicRegionDetector(directory.resolve("${MangaArtifacts.detector.id}.onnx"))
        page=try { MangaPageOcr(detector,DesktopMangaRegionReader(directory)) }
        catch(error: Throwable) {detector.close();throw error}
    }
    override fun recognize(pixels: IntArray,width: Int,height: Int,progress: PageOcrProgress): List<PageText> {
        check(!closed.get()) { "Manga OCR is closed" }
        return page.recognize(pixels,width,height,progress)
    }
    override fun cancel() {if(!closed.get())page.cancel()}
    override fun close() {if(closed.compareAndSet(false,true))page.close()}
    companion object {
        fun runtimeIssue(): String? = DesktopNativeLibraries.readiness("hearth_desktop_ocr")
        /** Package check without opening a model, image, device or remote connection. */
        fun verifyRuntimeContract() {
            check(runtimeIssue() == null) { "Bundled Manga OCR runtime is unavailable" }
            fun rejectsRetiredHandle(call: () -> Unit) {
                var rejected = false
                try { call() } catch (e: IllegalStateException) { rejected = e.message.orEmpty().contains("closed",true) }
                check(rejected) { "Packaged OCR JNI did not reject a retired handle" }
            }
            rejectsRetiredHandle { ComicNative.detect(0,intArrayOf(0),1,1) }
            rejectsRetiredHandle { JapaneseNative.recognize(0,intArrayOf(0),1,1) }
        }
        private fun sha(path: Path): String {
            val digest=MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
    }
}
private class DesktopComicRegionDetector(model: Path): ComicRegionDetector {
    private val handle=AtomicLong(ComicNative.create(model.toAbsolutePath().toString().toByteArray(Charsets.UTF_8)))
    override fun detect(pixels: IntArray,width: Int,height: Int): List<ComicDetection> {
        val id=handle.get();check(id!=0L) {"Comic detector is closed"}
        return ComicNative.detect(id,pixels,width,height).map {
            check(it.size==6) {"Invalid native comic detection"}
            ComicDetection(OcrRect(it[0],it[1],it[2],it[3]),it[5],it[4]/10000f)
        }
    }
    override fun cancel() {handle.get().takeIf {it!=0L}?.let(ComicNative::cancel)}
    override fun close() {handle.getAndSet(0L).takeIf {it!=0L}?.let(ComicNative::destroy)}
}
private class DesktopMangaRegionReader(directory: Path): MangaRegionReader {
    private val vocabulary: List<String> = javaClass.getResourceAsStream("/ocr/manga.json")?.use { input ->
        val bytes=input.readBytes()
        check(bytes.size<=100_000 && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==MangaArtifacts.vocabularySha256) {"Packaged Manga vocabulary checksum failed"}
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map {it.jsonPrimitive.content}.also {check(it.size==6144)}
    } ?: error("Packaged Manga vocabulary is missing")
    private val handle=AtomicLong(JapaneseNative.create(1,
        directory.resolve("${MangaArtifacts.encoder.id}.onnx").toAbsolutePath().toString().toByteArray(Charsets.UTF_8),
        directory.resolve("${MangaArtifacts.decoder.id}.onnx").toAbsolutePath().toString().toByteArray(Charsets.UTF_8),byteArrayOf()))
    override fun read(pixels: IntArray,width: Int,height: Int): String {
        val id=handle.get();check(id!=0L) {"Manga reader is closed"}
        return JapaneseNative.recognize(id,pixels,width,height).joinToString("\n") { row ->
            check(row.size>=5) {"Invalid Manga reader result"}
            buildString {for(token in row.drop(5)) {
                check(token in vocabulary.indices) {"Manga token is outside its vocabulary"}
                if(token !in 0..3)append(vocabulary[token].removePrefix("##"))
            }}.trim()
        }
    }
    override fun cancel() {handle.get().takeIf {it!=0L}?.let(JapaneseNative::cancel)}
    override fun close() {handle.getAndSet(0L).takeIf {it!=0L}?.let(JapaneseNative::destroy)}
}
