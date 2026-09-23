package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.caption.speechBackend
import com.sal7one.transiber.downloads.DownloadSpec
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ModelSourcesTest {
    @Test fun everySupportedNativeProfileHasSourcesAndAnHonestInstallPath() {
        val sources = CaptionEngineChoice.entries.filter { it.speechBackend != null }.flatMap(ModelSources::speech)
        assertEquals(SpeechProfile.entries.map { it.id }.toSet(), sources.map { it.id }.toSet())
        sources.forEach { source ->
            assertEquals("https", URI(source.publisher).scheme)
            assertEquals("https", URI(source.files).scheme)
            source.download?.let { url ->
                DownloadSpec.parse(url, url.substringAfterLast('/'))
                assertTrue(source.installation.contains("automatically"))
                assertFalse(source.installation.contains("hearth-speech.json"))
                val artifact = SpeechDownloads.find(source.id)!!
                assertEquals(url, artifact.url)
                assertTrue(artifact.bytes > 0)
                assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
    }
    @Test fun unverifiedQwen17AndCloudNeverPretendToBeReadyDownloads() {
        assertNull(ModelSources.speech(CaptionEngineChoice.QWEN).single { it.id == "qwen3-asr-1.7b" }.download)
        assertTrue(ModelSources.speech(CaptionEngineChoice.CLOUD).isEmpty())
        assertTrue(ModelSources.speech(CaptionEngineChoice.WHISPER).single().installation.contains(".bin"))
        assertTrue(ModelSources.speech(CaptionEngineChoice.VOSK).single().installation.contains("extract"))
        assertTrue(ModelSources.marian.installation.contains("English → Arabic"))
    }

    @Test fun whisperArtifactsArePinnedAndGroupedByCheckpointAndQuant() {
        val artifacts = SpeechArtifactCatalog.all.filter { it.kind == SpeechArtifactKind.WHISPER }
        assertEquals(27, artifacts.size)
        assertEquals(27, artifacts.map { it.id }.distinct().size)
        assertEquals(setOf("tiny", "tiny.en", "base", "base.en", "small", "small.en", "medium", "medium.en", "large-v3-turbo"),
            artifacts.map { it.checkpoint }.toSet())
        artifacts.forEach {
            assertTrue(it.url.contains("/${WhisperDownloads.REVISION}/"))
            assertTrue(it.bytes > 0)
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.publisherUrl.startsWith("https://"))
        }
        assertTrue(artifacts.filter { it.checkpoint.endsWith(".en") }.all { it.languages == setOf("en") })
    }
}
