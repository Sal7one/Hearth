package com.sal7one.transiber.settings

import android.app.DownloadManager
import com.sal7one.transiber.downloads.FileDownload
import org.junit.Assert.*
import org.junit.Test

class VoicePackageActionsTest {
    private fun row(id: Long,phase: String,model: String="voice-oute-0")=FileDownload(id,"model.bin",
        when(phase){"Failed"->DownloadManager.STATUS_FAILED;"Installed"->DownloadManager.STATUS_SUCCESSFUL;else->DownloadManager.STATUS_RUNNING},
        0,10,10,phase,error=if(phase=="Failed")"Actual host failure" else "",modelId=model)
    @Test fun newerFailedRepairRemainsVisibleDespiteAnOlderInstalledRecord() {
        val newest=row(-2,"Failed");val older=row(-1,"Installed")
        assertEquals(listOf(newest),voicePackageRecords(listOf(newest,older),setOf("voice-oute-0")))
    }
    @Test fun activeRepairTakesPrecedenceOverPastFailureAndOtherFamiliesAreExcluded() {
        val active=row(-1,"Downloading")
        assertEquals(listOf(active),voicePackageRecords(listOf(row(-3,"Failed"),row(-2,"Downloading","voice-moss-nano-0"),active),setOf("voice-oute-0")))
    }
}
