package com.sal7one.transiber.settings

import com.sal7one.transiber.models.SpeechArtifact
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.transiber.models.SpeechArtifactCatalog
import org.junit.Assert.*
import org.junit.Test

class SpeechBrowserPresentationTest {
    @Test fun livePartialHintsDescribeTheHearthAdapterRatherThanThePublisherName() {
        val groups = SpeechArtifactCatalog.all.groupBy { it.family }
        listOf("vosk", "nemotron").forEach { family ->
            assertTrue(groups.getValue(family).all { speechDecodeMode(it) == SpeechDecodeMode.STREAMING_PARTIALS })
        }
        assertTrue(groups.getValue("whisper").all { speechDecodeMode(it) == SpeechDecodeMode.WINDOWED_PARTIALS })
        listOf("moonshine", "qwen3-asr", "omnilingual", "phonon", "transcribepsy").forEach { family ->
            assertTrue(groups.getValue(family).all { speechDecodeMode(it) == SpeechDecodeMode.UTTERANCE_FINALS })
        }
    }
    private fun artifact(id: String, checkpoint: String = "tiny", bytes: Long = 100,
        languages: Set<String> = setOf("en", "ar"), release: String? = "2022-09-21", artifactDate: String? = "2024-10-01") =
        SpeechArtifact(id, "whisper", checkpoint, "Q5_1", "Whisper $checkpoint", SpeechArtifactKind.WHISPER,
            "https://example.com/$id.bin", bytes, "a".repeat(64), "fixed", release, artifactDate,
            languages, "MIT", "https://example.com", installedBytes = bytes)

    @Test fun precisionVariantsShareOneCardAndRemainAvailableWhenInstalledFilterIsUsed() {
        val models = listOf(artifact("tiny-q5"), artifact("tiny-q8", bytes = 200), artifact("english", "tiny.en", languages = setOf("en")))
        assertEquals(2, speechCheckpointGroups(models, "", "", "", false, emptySet()).size)
        val groups = speechCheckpointGroups(models, "whisper", "ar", "tiny", true, setOf("tiny-q8"))
        assertEquals(1, groups.size)
        assertEquals(setOf("tiny-q5", "tiny-q8"), groups.single().map { it.id }.toSet())
        assertTrue(speechCheckpointGroups(models, "vosk", "", "", false, emptySet()).isEmpty())
    }

    @Test fun currentAndInstalledPrecisionTakePriorityOverSmallestDownload() {
        val small = artifact("small", bytes = 100)
        val installed = artifact("installed", bytes = 200)
        val current = artifact("current", bytes = 300)
        val variants = listOf(small, installed, current)
        assertEquals(current, preferredSpeechVariant(variants, setOf("installed"), "current"))
        assertEquals(installed, preferredSpeechVariant(variants, setOf("installed"), ""))
        assertEquals(small, preferredSpeechVariant(variants, emptySet(), ""))
    }

    @Test fun installedCardsSortFirstAndDatedArtifactsAreNotLostBehindOlderReleases() {
        val old = artifact("old", "old", release = "2022-09-21")
        val recent = artifact("recent", "recent", release = null, artifactDate = "2026-10-14")
        assertEquals("recent", speechCheckpointGroups(listOf(old, recent), "", "", "", false, emptySet()).first().single().id)
        assertEquals("old", speechCheckpointGroups(listOf(old, recent), "", "", "", false, setOf("old")).first().single().id)
        assertEquals("Whisper Tiny", speechCheckpointTitle(artifact("test", "tiny.en")))
    }
}
