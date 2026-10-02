package com.sal7one.transiber.byok

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CloudVoiceFilesTest {
    @Test fun removesAudioAfterCompletionOrPlaybackFailure() = runBlocking {
        val dir=Files.createTempDirectory("voice-test").toFile()
        try {
            val audio=CloudVoiceProtocol.Audio(byteArrayOf(1,2,3),"mp3")
            withCloudVoiceFile(dir,audio) { file -> assertArrayEquals(audio.bytes,file.readBytes()) }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
            val error=runCatching { withCloudVoiceFile(dir,audio) { throw IllegalStateException("playback failed") } }.exceptionOrNull()
            assertEquals("playback failed",error?.message)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun stopWhilePlayingStillDeletesFile() = runBlocking {
        val dir=Files.createTempDirectory("voice-test").toFile(); val started=CompletableDeferred<Unit>()
        try {
            val work=launch {
                withCloudVoiceFile(dir,CloudVoiceProtocol.Audio(byteArrayOf(1),"mp3")) {
                    assertTrue(it.exists()); started.complete(Unit); awaitCancellation()
                }
            }
            withTimeout(5000) { started.await() }; work.cancelAndJoin()
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
