package com.sal7one.transiber.document

import org.junit.Assert.*
import org.junit.Test

class DocumentTextLanguageTest {
    @Test fun explicitTextLanguageIsIndependentOfImageReader() {
        assertEquals("ar", DocumentTextLanguage.read("ar", "glm-ocr", setOf("en", "zh")))
        assertEquals("ru", DocumentTextLanguage.read("ru", "english", setOf("en")))
    }
    @Test fun absentOrCorruptChoiceDefaultsToReaderWithoutUsingCameraState() {
        assertEquals("ar", DocumentTextLanguage.read(null, "arabic", setOf("ar", "en")))
        assertEquals("zh", DocumentTextLanguage.read(null, "cjk", setOf("en", "zh", "ja")))
        assertEquals("en", DocumentTextLanguage.read("auto", "glm-ocr", setOf("en", "zh")))
        assertEquals("ja", DocumentTextLanguage.read(null, "meiki", setOf("ja")))
    }
}
