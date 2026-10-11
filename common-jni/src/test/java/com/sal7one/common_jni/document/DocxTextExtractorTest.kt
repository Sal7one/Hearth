package com.sal7one.common_jni.document

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun xml(body: String) = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>".toByteArray()
    private fun extract(bytes: ByteArray) = DocxTextExtractor.extract(ByteArrayInputStream(bytes))
    @Test fun paragraphsRunsArabicAndChineseKeepOrder() {
        val result = extract(zip("word/document.xml" to xml("<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>العربية 中文 &amp; world</w:t></w:r></w:p><w:p><w:r><w:t>Next</w:t><w:tab/><w:t>column</w:t><w:br/><w:t>line</w:t></w:r></w:p>")))
        assertEquals("Hello العربية 中文 & world\nNext\tcolumn\nline", result.text)
    }
    @Test fun trackedDeletionsDoNotBecomeVisibleText() {
        val result = extract(zip("word/document.xml" to xml("<w:p><w:r><w:t>Keep</w:t></w:r><w:del><w:r><w:t>deleted</w:t></w:r></w:del><w:ins><w:r><w:t> added</w:t></w:r></w:ins></w:p>")))
        assertEquals("Keep added", result.text)
    }
    @Test fun imageCallbackRunsOnceWithoutExtractingPaths() {
        val image = byteArrayOf(1, 2, 3)
        var count = 0
        val result = DocxTextExtractor.extract(ByteArrayInputStream(zip("word/media/photo.png" to image, "word/document.xml" to xml("<w:p><w:r><w:t>Text</w:t></w:r></w:p>"))), onImage = { name, bytes ->
            assertEquals("word/media/photo.png", name); assertArrayEquals(image, bytes); count++
        })
        assertEquals(1, result.images); assertEquals(1, count); assertEquals("Text", result.text)
    }
    @Test fun textOnlyModeCountsImagesWithoutDeliveringThem() {
        val result = DocxTextExtractor.extract(ByteArrayInputStream(zip("word/media/photo.png" to byteArrayOf(1),
            "word/document.xml" to xml("<w:p><w:r><w:t>Text only</w:t></w:r></w:p>"))),
            readImages = false, onImage = { _, _ -> fail("Text-only pass must not allocate/deliver images") })
        assertEquals("Text only", result.text); assertEquals(1, result.images)
    }
    @Test fun rejectsTraversalAndForeignZip() {
        assertThrows(IllegalArgumentException::class.java) { extract(zip("../document.xml" to xml(""))) }
        assertThrows(IllegalArgumentException::class.java) { extract(zip("notes.txt" to "hello".toByteArray())) }
    }
    @Test fun directoryPayloadCannotBypassExpandedByteLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(zip("word/" to byteArrayOf(1), "word/document.xml" to xml("")))
        }
        assertEquals("OK", extract(zip("word/" to byteArrayOf(),
            "word/document.xml" to xml("<w:p><w:r><w:t>OK</w:t></w:r></w:p>"))).text)
    }
    @Test fun rejectsDtdAndExternalEntities() {
        val bytes = "<!DOCTYPE w:document [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>".toByteArray() + xml("<w:p><w:r><w:t>&secret;</w:t></w:r></w:p>")
        assertThrows(IllegalArgumentException::class.java) { extract(zip("word/document.xml" to bytes)) }
    }
    @Test fun enforcesTextAndXmlLimits() {
        assertThrows(IllegalArgumentException::class.java) { extract(zip("word/document.xml" to xml("<w:p><w:r><w:t>${"a".repeat(DocxTextExtractor.MAX_TEXT_CHARS + 1)}</w:t></w:r></w:p>"))) }
        assertThrows(IllegalArgumentException::class.java) { extract(zip("word/document.xml" to ByteArray(DocxTextExtractor.MAX_XML_BYTES + 1))) }
    }
    @Test fun cancellationPropagatesAndCallerStreamStaysOpen() {
        var closed = false
        val stream = object : ByteArrayInputStream(zip("word/document.xml" to xml(""))) { override fun close() { closed = true } }
        assertThrows(InterruptedException::class.java) { DocxTextExtractor.extract(stream, checkCancelled = { throw InterruptedException("cancelled") }) }
        assertFalse(closed)
    }
    @Test fun corruptArchiveAndMalformedXmlAreFailures() {
        assertThrows(Exception::class.java) { extract(zip("word/document.xml" to "<w:broken>".toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) { extract(byteArrayOf(0, 1, 2)) }
    }
}
