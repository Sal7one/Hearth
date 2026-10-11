package com.sal7one.transiber.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sal7one.common_jni.document.DocxTextExtractor
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.transiber.ocr.OcrModels
import com.sal7one.transiber.ocr.OcrProfile
import com.sal7one.transiber.ocr.OcrText
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class DocumentPage(val label: String, val text: String, val ocrMs: Long = 0, val fromOcr: Boolean = false)
internal data class DocumentOcrState(
    val name: String = "", val processing: Boolean = false, val completed: Int = 0, val total: Int = 0,
    val pages: List<DocumentPage> = emptyList(), val error: String? = null, val cancelled: Boolean = false,
) {
    val text: String get() = pages.joinToString("\n\n") { it.text }
}

/** One document worker and one native OCR owner. Pages and embedded images are processed sequentially. */
internal class DocumentOcrViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutable = MutableStateFlow(DocumentOcrState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var engine: OcrEngine? = null
    private val models = OcrModels(File(context.filesDir, "ocr-models"))

    fun cancel() { engine?.cancel(); job?.cancel() }
    fun fail(error: Throwable) { mutable.update { it.copy(error = error.message ?: error.toString()) } }

    fun open(uri: Uri, profile: OcrProfile, scanEmbeddedImages: Boolean) {
        if (job?.isActive == true) return
        mutable.value = DocumentOcrState(processing = true)
        job = viewModelScope.launch(Dispatchers.IO) {
            val workerContext = currentCoroutineContext()
            var temporary: File? = null
            var lease: LocalWorkGate.Lease? = null
            var characters = 0
            fun append(page: DocumentPage) {
                characters += page.text.length
                require(characters <= DocumentLimits.MAX_TEXT_CHARS) { "Document text exceeds the 262,144-character limit. Split the document." }
                mutable.update { it.copy(pages = it.pages + page, completed = it.completed + 1) }
            }
            suspend fun ensureEngine() {
                if (engine == null) {
                    lease = LocalWorkGate.acquire("Document OCR")
                    ensureActive()
                    engine = withContext(NonCancellable) { models.open(context, profile) }
                }
                ensureActive()
            }
            fun recognizeLoaded(bitmap: Bitmap, label: String): DocumentPage {
                try {
                    workerContext.ensureActive()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val start = System.nanoTime()
                    val lines = checkNotNull(engine).recognize(pixels, bitmap.width, bitmap.height, 960)
                    workerContext.ensureActive()
                    return DocumentPage(label, lines.joinToString("\n") { OcrText.logical(it.text, profile.id == "arabic") },
                        (System.nanoTime() - start) / 1_000_000, true)
                } finally { bitmap.recycle() }
            }
            suspend fun recognize(bitmap: Bitmap, label: String): DocumentPage {
                try { ensureEngine() }
                catch (e: Throwable) { bitmap.recycle(); throw e }
                return recognizeLoaded(bitmap, label)
            }
            try {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.take(256) ?: "Document"
                mutable.update { it.copy(name = name) }
                val cache = File(context.cacheDir, "document-imports").apply { check(mkdirs() || isDirectory) { "Cannot create document cache" } }
                temporary = File.createTempFile("document-", ".import", cache)
                context.contentResolver.openInputStream(uri)?.use { input -> checkNotNull(temporary).outputStream().use { out ->
                    val buffer = ByteArray(65_536); var bytes = 0L
                    while (true) {
                        ensureActive(); val n = input.read(buffer); if (n < 0) break
                        bytes += n
                        require(bytes <= DocumentLimits.MAX_FILE_BYTES) { "Document exceeds the 64 MiB import limit. Split or compress it first." }
                        out.write(buffer, 0, n)
                    }
                } } ?: error("Cannot read this document URI")
                val file = checkNotNull(temporary)
                val header = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
                when (DocumentLimits.kind(header, name)) {
                    DocumentKind.PDF -> {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { pdf ->
                                require(pdf.pageCount in 1..DocumentLimits.MAX_PAGES) { "PDF must contain 1–200 pages. Split a larger document." }
                                mutable.update { it.copy(total = pdf.pageCount) }
                                for (index in 0 until pdf.pageCount) {
                                    ensureActive()
                                    pdf.openPage(index).use { page ->
                                        val existing = if (Build.VERSION.SDK_INT >= 35) PdfTextApi35.read(page) else ""
                                        if (existing.isNotBlank()) append(DocumentPage("${index + 1}", existing))
                                        else {
                                            val (width, height) = DocumentLimits.scaledSize(page.width, page.height)
                                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                            bitmap.eraseColor(Color.WHITE)
                                            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                            catch (e: Throwable) { bitmap.recycle(); throw e }
                                            append(recognize(bitmap, "${index + 1}"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    DocumentKind.DOCX -> {
                        // First validate and read text without allocating image bytes or loading OCR.
                        val result = file.inputStream().use { input -> DocxTextExtractor.extract(input,
                            readImages = false, checkCancelled = { ensureActive() }) }
                        characters += result.text.length
                        require(characters <= DocumentLimits.MAX_TEXT_CHARS) { "Document text exceeds the character limit" }
                        mutable.update { it.copy(pages = listOf(DocumentPage("DOCX", result.text)), completed = 1,
                            total = 1 + if (scanEmbeddedImages) result.images else 0) }
                        if (scanEmbeddedImages && result.images > 0) {
                            ensureEngine()
                            // No nested runBlocking: model/lease already exist and callback is pure IO/native work.
                            file.inputStream().use { input -> DocxTextExtractor.extract(input,
                                onImage = { imageName, bytes ->
                                    val image = decodeImage(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
                                    append(recognizeLoaded(image, imageName.substringAfterLast('/')))
                                }, checkCancelled = { ensureActive() }) }
                        }
                    }
                    DocumentKind.IMAGE -> {
                        mutable.update { it.copy(total = 1) }
                        append(recognize(decodeImage(ImageDecoder.createSource(file)), "1"))
                    }
                }
                ensureActive()
                if (mutable.value.text.isBlank()) error("No readable text was found. Choose another OCR language or a sharper scan.")
            } catch (e: CancellationException) { mutable.update { it.copy(cancelled = true) }; throw e }
            catch (e: Exception) { fail(e) }
            catch (e: LinkageError) { fail(e) }
            finally {
                withContext(NonCancellable) {
                    try { engine?.close() }
                    finally { engine = null; lease?.close(); temporary?.delete(); mutable.update { it.copy(processing = false) } }
                }
            }
        }
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val text = state.value.text
        check(text.isNotBlank()) { "There is no extracted text to export" }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("Cannot write to the selected destination")
    }

    private fun decodeImage(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = DocumentLimits.scaledSize(info.size.width, info.size.height)
        decoder.setTargetSize(width, height); decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    override fun onCleared() { cancel(); super.onCleared() }
}

internal enum class DocumentKind { PDF, DOCX, IMAGE }
internal object DocumentLimits {
    const val MAX_FILE_BYTES = 64L * 1_048_576
    const val MAX_TEXT_CHARS = 262_144
    const val MAX_PAGES = 200
    fun kind(header: ByteArray, name: String): DocumentKind = when {
        header.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> DocumentKind.PDF
        header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> DocumentKind.DOCX
        name.endsWith(".doc", true) -> error("Legacy .doc files are unsupported. Save as DOCX or PDF.")
        else -> DocumentKind.IMAGE
    }
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Image dimensions exceed the 100-megapixel limit" }
        val scale = minOf(1.0, 1_600.0 / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}

@androidx.annotation.RequiresApi(35)
private object PdfTextApi35 {
    fun read(page: PdfRenderer.Page): String = page.textContents.joinToString("\n") { it.text }
}
