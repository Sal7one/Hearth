package com.sal7one.transiber.byok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps ownership even if cancellation wins the IO-to-player dispatcher handoff. */
internal suspend fun <T> withCloudVoiceFile(directory: File?, audio: CloudVoiceProtocol.Audio,
    playback: suspend (File) -> T): T {
    var file: File? = null
    try {
        withContext(Dispatchers.IO) {
            val output = File.createTempFile("byok_tts", ".${audio.extension}", directory)
            file = output
            output.writeBytes(audio.bytes)
        }
        return playback(checkNotNull(file))
    } finally {
        withContext(Dispatchers.IO + NonCancellable) {
            file?.let { check(!it.exists() || it.delete()) { "Could not remove temporary voice audio" } }
        }
    }
}
