package com.sal7one.common_jni.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrTokensTest {
    @Test fun mangaRemovesSpecialTokensAndJoinsSubwords() {
        assertEquals("日本語",OcrTokens.decode(listOf(2,2,4,5,6,3),listOf("[PAD]","[UNK]","[CLS]","[SEP]","日","##本","語")))
    }
    @Test fun meikiSupportsSupplementaryUnicodeAndRejectsInvalidOutput() {
        assertEquals("日𠮷",OcrTokens.decode(listOf(0x65e5,0x20bb7),null))
        for(id in listOf(-1,0,0xd800,0x110000))assertTrue(runCatching {OcrTokens.decode(listOf(id),null)}.isFailure)
        assertTrue(runCatching {OcrTokens.decode(listOf(10),listOf("x"))}.isFailure)
    }
}
