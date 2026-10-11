package com.sal7one.transiber.notes

import org.junit.Assert.*
import org.junit.Test

class VoiceNoteAccessTest {
    @Test fun protectionIsOptionalAndUnlockDoesNotSurviveBackgroundOrRecreation() {
        var enabled = false
        val access = VoiceNoteAccess { enabled }
        assertFalse(access.locked)
        enabled = true
        assertTrue(access.locked)
        access.authenticationResult(true)
        assertFalse(access.locked)
        access.backgrounded()
        assertTrue(access.locked)
        access.authenticationResult(true)
        assertTrue(VoiceNoteAccess { enabled }.locked)
    }

    @Test fun canceledCredentialPromptKeepsNotesLocked() {
        val access = VoiceNoteAccess { true }
        access.authenticationResult(false)
        assertTrue(access.locked)
        access.authenticationResult(true)
        access.authenticationResult(false)
        assertTrue(access.locked)
    }
}
