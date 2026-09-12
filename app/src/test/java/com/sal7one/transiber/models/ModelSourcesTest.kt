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
                assertTrue(source.installation.contains("prepared speech ZIP"))
                assertTrue(source.installation.contains("hearth-speech.json"))
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
}
