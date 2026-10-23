package com.sal7one.transiber.benchmark

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchmarkCustomAudioStoreTest {
    private fun clip(value: Short = 1234) = BenchmarkAudio.fromPcm16(ShortArray(16_000) { value })

    private fun inFolder(block: (File, BenchmarkCustomAudioStore) -> Unit) {
        val folder = Files.createTempDirectory("benchmark-custom-audio").toFile()
        try { block(folder, BenchmarkCustomAudioStore(folder)) }
        finally { folder.deleteRecursively() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid recording to be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun privateCopyAndLabelSurviveNewStoreAndReplaceTogether() = inFolder { folder, store ->
        assertNull(store.load())
        val first = clip()
        assertEquals("my Arabic recording.wav", store.save(first, "  my Arabic recording.wav  ").label)
        val reloaded = BenchmarkCustomAudioStore(folder).load()!!
        assertEquals("my Arabic recording.wav", reloaded.label)
        assertEquals(first.sha256, reloaded.clip.sha256)
        assertArrayEquals(first.samples, reloaded.clip.samples)
        val second = clip(-4500)
        store.save(second, "next.wav")
        val replaced = BenchmarkCustomAudioStore(folder).load()!!
        assertEquals("next.wav", replaced.label)
        assertEquals(second.sha256, replaced.clip.sha256)
        assertEquals(setOf("custom.wav", "custom.json"), folder.listFiles()!!.map { it.name }.toSet())
        store.clear()
        assertNull(store.load())
    }

    @Test fun returnedInputOwnsCopyOfCallerSamples() = inFolder { _, store ->
        val source = clip()
        val saved = store.save(source, "speech.wav")
        source.samples.fill(0)
        assertTrue(saved.clip.samples.all { it == 1234.toShort() })
        assertEquals(saved.clip.sha256, store.load()!!.clip.sha256)
    }

    @Test fun malformedOrOversizedInputCannotReplaceCommittedRecording() = inFolder { _, store ->
        val original = clip()
        store.save(original, "saved.wav")
        rejects { store.save(BenchmarkAudio.Clip(ShortArray(31 * 16_000), "a".repeat(64)), "too-long.wav") }
        rejects { store.save(BenchmarkAudio.Clip(ShortArray(7_999), "a".repeat(64)), "too-short.wav") }
        rejects { store.save(original.copy(sha256 = "0".repeat(64)), "changed.wav") }
        rejects { store.save(original, " ") }
        rejects { store.save(original, "x".repeat(241)) }
        assertEquals(original.sha256, store.load()!!.clip.sha256)
        assertEquals("saved.wav", store.load()!!.label)
    }

    @Test fun damagedAudioAndMissingComponentAreExplicitErrors() = inFolder { folder, store ->
        store.save(clip(), "speech.wav")
        val file = File(folder, "custom.wav")
        file.writeBytes(file.readBytes().also { it[44] = (it[44].toInt() xor 1).toByte() })
        rejects { store.load() }
        store.save(clip(), "speech.wav")
        File(folder, "custom.json").delete()
        rejects { store.load() }
    }

    @Test fun oversizedAudioAndMetadataAreRejectedBeforeDecoding() = inFolder { folder, store ->
        store.save(clip(), "speech.wav")
        File(folder, "custom.wav").outputStream().use { stream ->
            stream.write(ByteArray(45 + BenchmarkAudio.MAX_SECONDS * 16_000 * 2))
        }
        rejects { store.load() }
        store.save(clip(), "speech.wav")
        File(folder, "custom.json").writeText("x".repeat(4097))
        rejects { store.load() }
    }

    @Test fun manifestCannotRedirectPrivateFileOrLieAboutPcm() = inFolder { folder, store ->
        store.save(clip(), "speech.wav")
        val manifest = File(folder, "custom.json")
        manifest.writeText(JSONObject(manifest.readText()).put("audio", "../other.wav").toString())
        rejects { store.load() }
        store.save(clip(), "speech.wav")
        manifest.writeText(JSONObject(manifest.readText()).put("pcmSha256", "a".repeat(64)).toString())
        rejects { store.load() }
    }

    @Test fun interruptedTwoFileCommitNeverReturnsStaleInputAndExplicitSaveRepairsIt() = inFolder { folder, store ->
        store.save(clip(), "first.wav")
        File(folder, "custom.pending").writeBytes(byteArrayOf(1))
        rejects { BenchmarkCustomAudioStore(folder).load() }
        val next = clip(-2500)
        val repaired = store.save(next, "second.wav")
        assertEquals("second.wav", repaired.label)
        assertEquals(next.sha256, BenchmarkCustomAudioStore(folder).load()!!.clip.sha256)
    }

    @Test fun failedMetadataCommitDoesNotPretendAnotherRecordingWasSaved() = inFolder { folder, store ->
        store.save(clip(), "first.wav")
        val manifest = File(folder, "custom.json")
        check(manifest.delete() && manifest.mkdir())
        try { store.save(clip(-2500), "second.wav"); fail("Expected failed metadata commit") }
        catch (_: java.io.IOException) { }
        rejects { store.load() }
        check(manifest.delete())
        assertEquals("second.wav", store.save(clip(-2500), "second.wav").label)
    }

    @Test fun symbolicLinksCannotReadOrOverwriteFilesOutsideStore() = inFolder { folder, store ->
        val outside = File.createTempFile("benchmark-outside", ".wav")
        try {
            outside.writeText("keep me")
            Files.createSymbolicLink(File(folder, "custom.wav").toPath(), outside.toPath())
            rejects { store.load() }
            rejects { store.save(clip(), "speech.wav") }
            assertEquals("keep me", outside.readText())
        } finally { outside.delete() }
    }
}
