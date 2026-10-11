package com.sal7one.transiber.document

import com.sal7one.common_jni.document.DocxTextExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the library parser through Android's XML implementation, not just desktop Xerces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class DocumentAndroidXmlTest {
    private fun archive(xml: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry() }
    }.toByteArray()
    @Test fun unicodeParagraphsAndEntitiesUseAndroidParser() {
        val xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>مرحبا 中文 &amp; Hearth</w:t></w:r></w:p></w:body></w:document>"
        assertEquals("مرحبا 中文 & Hearth", DocxTextExtractor.extract(archive(xml).inputStream()).text)
    }
    @Test fun doctypeFailsBeforeAndroidParserCanResolveEntity() {
        assertThrows(IllegalArgumentException::class.java) {
            DocxTextExtractor.extract(archive("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///proc/self/status'>]><document>&x;</document>").inputStream())
        }
    }
}
