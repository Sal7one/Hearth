package com.sal7one.transiber.voice
import org.junit.Assert.*
import org.junit.Test
class VoiceSelectionTest {
    @Test fun systemPlaybackDoesNotRerouteTheSavedCustomEngine() {
        assertEquals("system",VoiceSelection.backend(VoicePlaybackMode.SYSTEM,"remote","remote",true))
        assertEquals("remote",VoiceSelection.backend(VoicePlaybackMode.CUSTOM,"system","remote",true))
        assertEquals("system",VoiceSelection.backend(VoicePlaybackMode.DEFAULT,"system","remote",true))
    }
    @Test fun existingNativeSelectionMigratesAndNewCustomSelectionWins() {
        assertEquals("supertonic",VoiceSelection.customBackend("supertonic",null,true))
        assertEquals("remote",VoiceSelection.customBackend("remote","supertonic",true))
        assertEquals("supertonic",VoiceSelection.customBackend("system","supertonic",false))
    }
    @Test fun missingCustomOrOfflineRemoteNeverSilentlyUsesAndroidForCustomButton() {
        assertNull(VoiceSelection.customBackend("system",null,true))
        assertNull(VoiceSelection.customBackend("system","remote",false))
        assertThrows(IllegalStateException::class.java){VoiceSelection.backend(VoicePlaybackMode.CUSTOM,"system",null,true)}
        assertThrows(IllegalStateException::class.java){VoiceSelection.backend(VoicePlaybackMode.CUSTOM,"remote","remote",false)}
    }
}
