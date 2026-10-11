package com.sal7one.transiber.voice

/** One audible TTS owner across Android/native/server/cloud consumers. Does not stop capture or ASR. */
internal object VoicePlaybackOwner {
    private data class Entry(val owner: Any, val stop: () -> Unit)
    private var entry: Entry? = null

    fun claim(owner: Any, stop: () -> Unit) {
        val previous = synchronized(this) {
            if (entry?.owner === owner) return
            val old = entry
            entry = Entry(owner, stop)
            old
        }
        // Stop outside the monitor: cancellation callbacks may release the previous owner.
        previous?.stop?.invoke()
    }

    fun release(owner: Any) = synchronized(this) {
        if (entry?.owner === owner) entry = null
    }
}
