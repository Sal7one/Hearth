package com.sal7one.transiber.downloads

import android.app.DownloadManager
import com.sal7one.transiber.translation.MarianPackage
import org.junit.Assert.*
import org.junit.Test

class DownloadQueueGroupsTest {
    private val pair = MarianPackage.pairs.first()
    private fun row(index: Int, phase: String = "Complete", id: Long = -index.toLong() - 1) = FileDownload(id,
        pair.parts[index].downloadName, when (phase) { "Complete", "Installed" -> DownloadManager.STATUS_SUCCESSFUL
            "Failed" -> DownloadManager.STATUS_FAILED; else -> DownloadManager.STATUS_RUNNING },
        0, pair.parts[index].bytes, pair.parts[index].bytes, phase, modelId = pair.parts[index].id,
        installedId = if (phase == "Installed") "verified-private-model" else "")

    @Test fun aPartialPackageIsOneCardAndNeverClaimsCompleteInstallation() {
        val group = downloadQueueGroups(listOf(row(0))).single()
        assertTrue(group.multipart)
        assertEquals(pair.id, group.modelId)
        assertEquals(4, group.required.size)
        assertEquals(1, group.completed)
        assertNull(group.stage)
        assertNull(group.installedRecord)
    }
    @Test fun allFilesAreNeededBeforeReadyAndInstalledIsDistinct() {
        val complete = downloadQueueGroups(pair.parts.indices.map { row(it) }).single()
        assertEquals(DownloadStage.READY, complete.stage)
        val installed = downloadQueueGroups(pair.parts.indices.map { row(it, if (it == 3) "Installed" else "Complete") }).single()
        assertEquals(DownloadStage.INSTALLED, installed.stage)
        assertEquals(4, installed.completed)
        assertNotNull(installed.installedRecord)
    }
    @Test fun failedDuplicateDoesNotOverrideAHealthyCopyOrDoubleCountBytes() {
        val records = pair.parts.indices.map { row(it) } + row(0, "Failed", -99)
        val group = downloadQueueGroups(records).single()
        assertEquals(4, group.completed)
        assertEquals(pair.downloadBytes, group.transferred)
        assertNull(group.failure)
        assertEquals(DownloadStage.READY, group.stage)
        assertEquals(5, group.rows.size) // Copies remain recoverable in file details.
    }
    @Test fun verificationTakesPrecedenceOverQueuedComponentsAndHasNoTransferPercentage() {
        val group = downloadQueueGroups(listOf(row(0, "Waiting"), row(1, "Verifying"))).single()
        assertEquals(DownloadStage.VERIFYING, group.stage)
        assertNull(group.fraction)
        assertEquals(0, group.priority)
    }
    @Test fun unrelatedFilesRemainIndividuallyManageable() {
        val files = listOf(FileDownload(-100,"first.bin",DownloadManager.STATUS_SUCCESSFUL,0,10,10),
            FileDownload(-101,"second.bin",DownloadManager.STATUS_SUCCESSFUL,0,20,20))
        val groups = downloadQueueGroups(files)
        assertEquals(2, groups.size)
        assertTrue(groups.all { !it.multipart && it.modelId == null })
    }
    @Test fun aJisrRuntimeArchiveIsOneModelCardWithZipTransferBytesAndAnIndependentUseAction() {
        val spec=checkNotNull(com.sal7one.common_jni.translation.Ct2Catalog.find("ct2-jisr-50m-en-ar-int8"))
        val part=com.sal7one.transiber.translation.Ct2Package.parts(spec).single()
        val complete=FileDownload(-700,part.downloadName,DownloadManager.STATUS_SUCCESSFUL,0,
            part.file.bytes,part.file.bytes,"Complete",modelId=part.id)
        val group=downloadQueueGroups(listOf(complete)).single()
        assertEquals(spec.id,group.modelId)
        assertFalse(group.multipart)
        assertEquals(mapOf(part.id to spec.archive!!.bytes),group.required)
        assertEquals(spec.archive!!.bytes,group.total)
        assertEquals(group.total,group.transferred)
        assertEquals(DownloadStage.READY,group.stage)
        assertNull(group.installedRecord)
        val installed=downloadQueueGroups(listOf(complete.copy(phase="Installed",installedId=spec.id))).single()
        assertEquals(DownloadStage.INSTALLED,installed.stage)
        assertEquals(spec.id,installed.installedRecord?.installedId)
    }
    @Test fun speechDecoderAndEncoderShareOneCardAndBothAreRequired() {
        val source = com.sal7one.transiber.models.SpeechComponents.groups.single()
        val rows = source.parts.mapIndexed { index, part -> FileDownload(-100L-index, part.fileName,
            DownloadManager.STATUS_SUCCESSFUL,0,part.bytes,part.bytes,"Complete",modelId=part.id) }
        val partial = downloadQueueGroups(rows.take(1)).single()
        assertEquals(source.id, partial.modelId)
        assertEquals(1, partial.completed)
        assertNull(partial.stage)
        val complete = downloadQueueGroups(rows).single()
        assertEquals(DownloadStage.READY, complete.stage)
        assertEquals(source.bytes, complete.total)
        assertEquals(source.bytes, complete.transferred)
        assertNull(complete.installedRecord)
    }
    @Test fun supertonicNeedsCoreAndOnlyRequestedStylesRatherThanAllTenVoices() {
        val catalog = com.sal7one.transiber.voice.VoiceCatalog
        val requested = catalog.file("F2.json")
        val rows = (catalog.core + requested).mapIndexed { index, asset -> FileDownload(-200L-index,asset.filename,
            DownloadManager.STATUS_SUCCESSFUL,0,asset.bytes,asset.bytes,"Installed",modelId=asset.id,installedId=asset.id) }
        val group = downloadQueueGroups(rows).single()
        assertEquals("supertonic", group.voiceFamily)
        assertEquals((catalog.core + requested).map { it.id }.toSet(), group.required.keys)
        assertNotNull(group.installedRecord)
        assertFalse(catalog.file("F1.json").id in group.required)
    }
    @Test fun aVoicePackageNeedsAllRequiredFileInstallsAndNeverMixesFamilies() {
        val catalog = com.sal7one.transiber.voice.VoiceCatalog
        val omni = catalog.familyAssets("omnivoice")
        val rows = omni.mapIndexed { index, asset -> FileDownload(-300L-index,asset.filename,
            DownloadManager.STATUS_SUCCESSFUL,0,asset.bytes,asset.bytes,if(index==0)"Installed" else "Complete",
            modelId=asset.id,installedId=if(index==0)asset.id else "") }
        val ready = downloadQueueGroups(rows).single()
        assertEquals("omnivoice", ready.voiceFamily)
        assertEquals(2, ready.completed)
        assertNull(ready.installedRecord)
        assertEquals(DownloadStage.READY, ready.stage)
        val moss = catalog.familyAssets("moss-nano").first()
        val groups = downloadQueueGroups(rows + FileDownload(-400,moss.filename,DownloadManager.STATUS_SUCCESSFUL,
            0,moss.bytes,moss.bytes,"Installed",modelId=moss.id,installedId=moss.id))
        assertEquals(setOf("omnivoice", "moss-nano"),groups.mapNotNull { it.voiceFamily }.toSet())
        assertNull(groups.single { it.voiceFamily == "moss-nano" }.installedRecord)
    }
}
