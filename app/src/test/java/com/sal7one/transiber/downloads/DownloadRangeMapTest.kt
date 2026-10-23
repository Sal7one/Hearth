package com.sal7one.transiber.downloads

import com.sal7one.hearth.tts.magpie.MagpieCatalog
import org.junit.Assert.*
import org.junit.Test

class DownloadRangeMapTest {
    @Test fun resumeMapsSavedPayloadOffsetsAcrossSourceGaps() {
        val ranges = listOf(0L..9L, 90L..99L)
        assertEquals(listOf(DownloadRangeMap.Slice(0, 9, 0), DownloadRangeMap.Slice(90, 99, 10)), DownloadRangeMap.remaining(ranges, 0, 100))
        assertEquals(listOf(DownloadRangeMap.Slice(4, 9, 4), DownloadRangeMap.Slice(90, 99, 10)), DownloadRangeMap.remaining(ranges, 4, 100))
        assertEquals(listOf(DownloadRangeMap.Slice(90, 99, 10)), DownloadRangeMap.remaining(ranges, 10, 100))
        assertEquals(listOf(DownloadRangeMap.Slice(93, 99, 13)), DownloadRangeMap.remaining(ranges, 13, 100))
        assertTrue(DownloadRangeMap.remaining(ranges, 20, 100).isEmpty())
    }
    @Test fun invalidOffsetsAndSourceRangesNeverBecomeRequests() {
        assertThrows(IllegalArgumentException::class.java) { DownloadRangeMap.remaining(listOf(0L..9L), -1, 10) }
        assertThrows(IllegalArgumentException::class.java) { DownloadRangeMap.remaining(listOf(0L..9L), 11, 10) }
        assertThrows(IllegalArgumentException::class.java) { DownloadRangeMap.remaining(listOf(0L..10L), 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { DownloadRangeMap.remaining(listOf(-1L..5L), 0, 10) }
    }
    @Test fun magpieQueueContainsTheActualAuthenticatedPayloadRatherThanTheFullArchive() {
        val asset = checkNotNull(DownloadAssets.find(DownloadAssets.MAGPIE_TOKENIZER))
        assertEquals(MagpieCatalog.tokenizerSegmentsBytes, asset.bytes)
        assertEquals(MagpieCatalog.tokenizerSegmentsSha256, asset.sha256)
        assertEquals(31_076_864L, asset.ranges.sumOf { it.last - it.first + 1 })
        assertEquals(1_470_208_000L, asset.sourceBytes)
        assertEquals(MagpieCatalog.ranges, asset.ranges)
        assertTrue(asset.bytes < checkNotNull(asset.sourceBytes) / 40)
        val row = FileDownload(-1, "tokenizer", 1, 0, 0, asset.bytes, phase = "Waiting", modelId = asset.id)
        val group = downloadQueueGroups(listOf(row)).single()
        assertEquals("magpie", group.voiceFamily)
        assertEquals(3, group.required.size)
        assertEquals(MagpieCatalog.downloadBytes, group.total)
    }
}
