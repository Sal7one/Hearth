package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SaudiBenchmarkPackTest {
    @Test fun privateSaudiClipsRoundTripWithExactReferencesAndStableHashes() {
        val dir = Files.createTempDirectory("saudi-benchmark").toFile()
        try {
            val pack = SaudiBenchmarkPack(dir)
            assertNull(pack.load())
            val samples = ShortArray(16_000) { if (it % 2 == 0) 4000 else -4000 }
            val clip = BenchmarkAudio.fromPcm16(samples)
            val first = pack.save(clip, "  وش رايك نطلع بعد المغرب؟  ")
            assertEquals("ar", first.cases.single().source)
            assertEquals("وش رايك نطلع بعد المغرب؟", first.cases.single().reference)
            assertEquals(clip.sha256, first.audioDummyHashForTest(dir))
            assertEquals(listOf("saudi-1", "silence-1000", "silence-2500"),
                first.selected(true, "ar", "", false).map { it.id })
            assertEquals(first.fingerprint, pack.load()!!.fingerprint)
            val second = pack.save(clip, "لا ترسل الملف الحين")
            assertEquals(2, second.cases.size)
            pack.clear()
            assertNull(pack.load())
            assertFalse(dir.resolve("saudi-1.wav").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsMissingReferenceAndChangedAudio() {
        val dir = Files.createTempDirectory("saudi-benchmark").toFile()
        try {
            val pack = SaudiBenchmarkPack(dir)
            val clip = BenchmarkAudio.fromPcm16(ShortArray(8_000) { 100 })
            try { pack.save(clip, " "); fail() } catch (_: IllegalArgumentException) { }
            pack.save(clip, "تمام")
            dir.resolve("saudi-1.wav").appendBytes(byteArrayOf(1))
            try { pack.load(); fail() } catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

    private fun BenchmarkSuite.audioDummyHashForTest(dir: java.io.File): String =
        BenchmarkAudio.decode(dir.resolve(checkNotNull(cases.single().audio)).readBytes()).sha256
}
