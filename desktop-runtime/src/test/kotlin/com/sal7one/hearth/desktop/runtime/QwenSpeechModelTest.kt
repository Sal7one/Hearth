package com.sal7one.hearth.desktop.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QwenSpeechModelTest {
    @Test fun pinsDescribeTheAuditedSixFilePackage() {
        assertEquals(6, QwenSpeechModel.files.size)
        assertEquals(987_015_347L, QwenSpeechModel.files.sumOf { it.bytes })
        assertEquals(30, QwenSpeechModel.languages.size)
        for (file in QwenSpeechModel.files) {
            assertEquals(64, file.sha256.length, file.path)
            assertEquals(file.sha256.lowercase(), file.sha256, file.path)
            assertEquals(true, file.bytes > 0, file.path)
        }
    }

    @Test fun sourceLanguagesNormalizeAndRejectUnknownValues() {
        assertEquals("en", QwenSpeechModel.language("en"))
        assertEquals("en", QwenSpeechModel.language("EN"))
        assertEquals("en", QwenSpeechModel.language("en-US"))
        assertEquals("auto", QwenSpeechModel.language("auto"))
        assertEquals("ar", QwenSpeechModel.language("ar"))
        assertFailsWith<IllegalArgumentException> { QwenSpeechModel.language("xx") }
        assertFailsWith<IllegalArgumentException> { QwenSpeechModel.language("English") }
        assertFailsWith<IllegalArgumentException> { QwenSpeechModel.language("") }
    }

    @Test fun detectedLanguageOnlyAcceptsCodes() {
        assertEquals("en", QwenSpeechModel.detected("en-US"))
        assertEquals("ar", QwenSpeechModel.detected("AR"))
        assertNull(QwenSpeechModel.detected("English"))
        assertNull(QwenSpeechModel.detected(""))
    }
}
