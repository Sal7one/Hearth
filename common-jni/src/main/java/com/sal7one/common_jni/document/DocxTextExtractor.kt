package com.sal7one.common_jni.document

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Streaming OOXML text reader. No paths are extracted; images are delivered one at a time.
 * The caller owns the input stream and image bytes only for the duration of its callback.
 * This is document text extraction, not a layout/track-changes or legacy .doc renderer.
 */
object DocxTextExtractor {
    const val MAX_ENTRIES = 1_024
    const val MAX_EXPANDED_BYTES = 128L * 1_048_576
    const val MAX_XML_BYTES = 8 * 1_048_576
    const val MAX_IMAGE_BYTES = 16 * 1_048_576
    const val MAX_IMAGES = 32
    const val MAX_TEXT_CHARS = 262_144

    data class Result(val text: String, val images: Int)

    fun extract(input: InputStream, readImages: Boolean = true, onImage: (String, ByteArray) -> Unit = { _, _ -> },
                checkCancelled: () -> Unit = {}): Result {
        var expanded = 0L
        var entries = 0
        var images = 0
        var text: String? = null
        val names = HashSet<String>()
        // Shield the caller's stream: closing ZIP inflater resources does not close the input.
        val shield = object : java.io.FilterInputStream(input) { override fun close() {} }
        ZipInputStream(shield).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "DOCX exceeds the archive entry limit" }
                val name = entry.name
                require(name.isNotBlank() && name.length <= 512 && !name.startsWith('/') &&
                    '\\' !in name && ':' !in name && name.split('/').none { it == ".." || it == "." } &&
                    name.none { it.code < 32 } && names.add(name)) { "Unsafe or duplicate DOCX archive path" }
                if (entry.isDirectory) {
                    // closeEntry() drains the inflater. Reject a directory payload before that
                    // drain, otherwise a compressed bomb can bypass the expanded-byte limits.
                    require(zip.read() < 0) { "DOCX directory entries must not contain data" }
                    zip.closeEntry(); continue
                }
                val xml = name == "word/document.xml"
                val image = name.startsWith("word/media/") && name.substringAfterLast('.').lowercase() in
                    setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
                val limit = if (xml) MAX_XML_BYTES.toLong() else if (image) MAX_IMAGE_BYTES.toLong() else MAX_EXPANDED_BYTES
                val out = if (xml || (image && readImages)) java.io.ByteArrayOutputStream() else null
                var size = 0L
                val buffer = ByteArray(32_768)
                while (true) {
                    checkCancelled()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count; expanded += count
                    require(size <= limit && expanded <= MAX_EXPANDED_BYTES) { "DOCX exceeds expanded content limits" }
                    out?.write(buffer, 0, count)
                }
                when {
                    xml -> text = parseXml(checkNotNull(out).toByteArray(), checkCancelled)
                    image -> {
                        require(++images <= MAX_IMAGES) { "DOCX exceeds the embedded image limit" }
                        if (readImages) onImage(name, checkNotNull(out).toByteArray())
                    }
                }
                zip.closeEntry()
            }
        }
        return Result(requireNotNull(text) { "This archive has no DOCX document.xml" }, images)
    }

    private fun parseXml(bytes: ByteArray, checkCancelled: () -> Unit): String {
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // OOXML uses UTF-8. Reject DTDs before parsing, independently of SAX provider features.
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true) &&
            !Regex("encoding\\s*=\\s*['\"](?!utf-8['\"])[^'\"]+", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            "DOCX XML entities or non-UTF-8 encoding are unsupported"
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External XML entities are forbidden") }
        val text = StringBuilder()
        var textDepth = 0
        var deletedDepth = 0
        fun append(value: String) {
            require(text.length.toLong() + value.length <= MAX_TEXT_CHARS) { "DOCX text exceeds the character limit" }
            text.append(value)
        }
        reader.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                checkCancelled()
                if (uri != "http://schemas.openxmlformats.org/wordprocessingml/2006/main" &&
                    uri != "http://purl.oclc.org/ooxml/wordprocessingml/main") return
                when (localName) {
                    "del" -> deletedDepth++
                    "t" -> if (deletedDepth == 0) textDepth++
                    "tab" -> if (deletedDepth == 0) append("\t")
                    "br", "cr" -> if (deletedDepth == 0) append("\n")
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (uri != "http://schemas.openxmlformats.org/wordprocessingml/2006/main" &&
                    uri != "http://purl.oclc.org/ooxml/wordprocessingml/main") return
                when (localName) {
                    "t" -> if (textDepth > 0) textDepth--
                    "del" -> if (deletedDepth > 0) deletedDepth--
                    "p" -> if (deletedDepth == 0 && text.isNotEmpty() && text.last() != '\n') append("\n")
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (textDepth > 0 && deletedDepth == 0) append(String(ch, start, length))
            }
        }
        reader.parse(InputSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
        return text.toString().trim()
    }
}
