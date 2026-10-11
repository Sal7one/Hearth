package com.sal7one.transiber.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackOwnerTest {
    @Test fun switchingBackendStopsOnlyPreviousSpeechOwner() {
        val native = Any(); val cloud = Any()
        var stoppedNative = 0; var stoppedCloud = 0
        try {
            VoicePlaybackOwner.claim(native) { stoppedNative++ }
            VoicePlaybackOwner.claim(native) { stoppedNative++ }
            assertEquals(0, stoppedNative)
            VoicePlaybackOwner.claim(cloud) { stoppedCloud++ }
            assertEquals(1, stoppedNative)
            assertEquals(0, stoppedCloud)
            VoicePlaybackOwner.release(native)
            VoicePlaybackOwner.claim(native) { stoppedNative++ }
            assertEquals(1, stoppedCloud)
        } finally { VoicePlaybackOwner.release(native); VoicePlaybackOwner.release(cloud) }
    }

    @Test fun oldOwnerCleanupCannotReleaseTheReplacement() {
        val old = Any(); val next = Any(); val last = Any()
        var stops = 0
        try {
            VoicePlaybackOwner.claim(old) { VoicePlaybackOwner.release(old) }
            VoicePlaybackOwner.claim(next) { stops++ }
            VoicePlaybackOwner.claim(last) { }
            assertEquals(1, stops)
        } finally { VoicePlaybackOwner.release(old); VoicePlaybackOwner.release(next); VoicePlaybackOwner.release(last) }
    }
}
