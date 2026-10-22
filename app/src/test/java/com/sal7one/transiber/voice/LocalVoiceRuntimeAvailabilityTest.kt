package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.supertonic.SupertonicSpeechEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalVoiceRuntimeAvailabilityTest {
    @Test fun supertonicDescriptorRequiresThePackagedRuntimeLikeTheOtherLocalFamilies() {
        val family = requireNotNull(LocalVoiceFamilies.find("supertonic"))
        assertFalse("The host has no Android Supertonic JNI", SupertonicSpeechEngine.available())
        assertEquals(SupertonicSpeechEngine.available(), family.runtimeAvailable())
        assertFalse("An unsupported ABI must not offer an executable Supertonic provider", family.runtimeAvailable())
    }
}
