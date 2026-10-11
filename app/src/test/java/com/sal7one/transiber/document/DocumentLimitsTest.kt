package com.sal7one.transiber.document

import org.junit.Assert.*
import org.junit.Test

class DocumentLimitsTest {
    @Test fun headerHasPriorityOverMisleadingExtension() {
        assertEquals(DocumentKind.PDF, DocumentLimits.kind("%PDF-1.7".toByteArray(), "photo.jpg"))
        assertEquals(DocumentKind.DOCX, DocumentLimits.kind(byteArrayOf(0x50, 0x4b, 3, 4), "renamed.jpg"))
        assertThrows(IllegalStateException::class.java) { DocumentLimits.kind(ByteArray(8), "old.doc") }
    }
    @Test fun scalingIsBoundedAndPreservesShape() {
        assertEquals(800 to 600, DocumentLimits.scaledSize(800, 600))
        assertEquals(1600 to 800, DocumentLimits.scaledSize(4000, 2000))
        assertEquals(1 to 1600, DocumentLimits.scaledSize(1, 1600))
        assertThrows(IllegalArgumentException::class.java) { DocumentLimits.scaledSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { DocumentLimits.scaledSize(Int.MAX_VALUE, Int.MAX_VALUE) }
    }
}
