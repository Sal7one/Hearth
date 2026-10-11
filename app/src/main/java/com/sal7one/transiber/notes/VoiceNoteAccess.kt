package com.sal7one.transiber.notes

/** A device-credential UI gate, not storage encryption or a Hearth account. Never persist unlocks. */
internal class VoiceNoteAccess(private val enabled: () -> Boolean) {
    private var authenticated = false
    val locked: Boolean get() = enabled() && !authenticated
    fun authenticationResult(success: Boolean) { authenticated = success }
    fun backgrounded() { authenticated = false }
}
