package com.sal7one.transiber.models

import com.sal7one.common_jni.speech.SpeechBackend
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.downloads.DownloadAssets
import com.sal7one.transiber.downloads.DownloadBudget
import org.junit.Assert.*
import org.junit.Test

class MoonshineDownloadTest {
    @Test fun everyMonolingualProfileHasPinnedInstallableArtifactsAndCorrectLicense() {
        val profiles = SpeechProfile.entries.filter { it.backend == SpeechBackend.MOONSHINE }
        val artifacts = SpeechArtifactCatalog.forEngine(CaptionEngineChoice.MOONSHINE)
        assertEquals(profiles.map { it.id }.toSet(), artifacts.map { it.id }.toSet())
        assertEquals(10, profiles.size)
        artifacts.forEach { artifact ->
            val profile = SpeechProfile.fromId(artifact.id)
            val download = requireNotNull(SpeechDownloads.find(artifact.id))
            assertEquals(setOf(requireNotNull(profile.fixedSourceLanguage)), artifact.languages)
            assertEquals(if (profile.fixedSourceLanguage == "en") "MIT" else "Moonshine Community License", artifact.license)
            assertEquals(setOf("model", "encoder", "decoder"), download.roles.keys)
            assertEquals("encoder_model.ort", download.roles["encoder"])
            assertEquals("decoder_model_merged.ort", download.roles["decoder"])
            assertEquals(download.sha256, requireNotNull(DownloadAssets.find(artifact.id)).sha256)
        }
    }

    @Test fun storageBudgetUsesVerifiedExpandedTreeInsteadOfCompressedHeuristic() {
        // Exact expanded sizes measured from the separately SHA-verified publisher archives.
        mapOf("moonshine-base-ar-v2" to 141523592L, "moonshine-base-es-v2" to 65357006L,
            "moonshine-tiny-ja-v2" to 72848066L, "moonshine-tiny-ko-v2" to 72433302L).forEach { (id, expanded) ->
            val asset = requireNotNull(DownloadAssets.find(id))
            assertEquals(expanded, requireNotNull(asset.installedBytes))
            assertEquals(expanded, requireNotNull(SpeechArtifactCatalog.find(id)!!.installedBytes))
            val budget = DownloadBudget.estimate(asset.bytes, asset.installedBytes)
            assertEquals(expanded, budget.installation)
            assertEquals(asset.bytes, budget.remainingDownload)
        }
    }
}
