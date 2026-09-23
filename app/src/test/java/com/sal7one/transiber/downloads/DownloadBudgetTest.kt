package com.sal7one.transiber.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadBudgetTest {
    @Test fun accountsForOriginalAndOneAtomicInstallWithoutDoubleCountingExistingFiles() {
        val b = DownloadBudget.estimate(100, 200, 40)
        assertEquals(60L, b.remainingDownload)
        assertEquals(260L + b.reserve, b.sameVolumeRequired)
        assertEquals(200L + b.reserve, b.privateVolumeRequired)
    }
    @Test fun rejectsOverflowAndInvalidProgress() {
        assertThrows(ArithmeticException::class.java) { DownloadBudget.estimate(Long.MAX_VALUE, null) }
        assertThrows(IllegalArgumentException::class.java) { DownloadBudget.estimate(10, 10, 11) }
    }
    @Test fun resumesOnlyMatchingRangesAndRestartsWhenRangeIgnored() {
        assertEquals(DownloadResume.Response(40,100), DownloadResume.response(206,40,"bytes 40-99/100",60,100,"\"a\"","\"a\""))
        assertEquals(DownloadResume.Response(0,100), DownloadResume.response(200,40,null,100,100,"\"a\"","\"b\""))
        assertThrows(IllegalArgumentException::class.java) { DownloadResume.response(206,40,"bytes 0-59/100",60,100,null,null) }
        assertThrows(IllegalArgumentException::class.java) { DownloadResume.response(206,40,"bytes 40-99/100",60,100,"\"a\"","\"b\"") }
        assertThrows(IllegalArgumentException::class.java) { DownloadResume.response(206,40,"bytes 40-99/101",60,100,null,null) }
    }
}
