package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhononLanguagesTest {
    private val english = EngineSelection("phonon-local", "phonon-2")
    private val multilingual = EngineSelection("phonon-local", "phonon-2.5")

    @Test fun englishAutoRetainsFixedIdentityAndRejectsOtherLanguages() {
        assertEquals("en", phononSource(english, "auto"))
        assertEquals("en", phononSource(english, "en-US"))
        assertEquals(setOf("en"), phononLanguages(english))
        assertFailsWith<IllegalArgumentException> { phononSource(english, "ru") }
    }

    @Test fun multilingualAllowsAll25ButNeverInventsALanguageForAuto() {
        assertEquals(25, phononLanguages(multilingual).size)
        PHONON_25_LANGUAGES.forEach { assertEquals(it, phononSource(multilingual, it)) }
        assertEquals("ru", phononSource(multilingual, "ru-RU"))
        assertEquals("auto", phononSource(multilingual, "auto"))
        for (unsupported in listOf("ar", "zh", "ja")) {
            assertFailsWith<IllegalArgumentException> { phononSource(multilingual, unsupported) }
        }
    }

    @Test fun customCheckpointsRequireExplicitProfileRatherThanFilenameInference() {
        assertFalse(phononMultilingual(english.copy(modelPath = "/models/phonon-2.5")))
        assertTrue(phononMultilingual(english.copy(modelId = "", options = mapOf("profile" to "phonon-2.5"))))
    }
}
