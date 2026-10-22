package com.sal7one.hearth.desktop.runtime

import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * File-only owner opt-in, with no model download, recording, playback, or provider request.
 * HEARTH_DESKTOP_GPU_SMOKE=1 HEARTH_GPU_BACKEND=metal|cuda
 * HEARTH_SMOKE_WHISPER=<pinned tiny/tiny.en Q5_1> HEARTH_SMOKE_TRANSLATION=<EuroLLM Q4_K_M>
 * HEARTH_SMOKE_WAV=<0.5–30s PCM WAV> [HEARTH_GPU_RECEIPT=<output.json>]
 */
class DesktopGpuRealModelSmokeTest {
    @Test fun selectedGpuCompletesRealWhisperAndTranslationInference() {
        assumeTrue("Set HEARTH_DESKTOP_GPU_SMOKE=1 with local pinned fixtures", System.getenv("HEARTH_DESKTOP_GPU_SMOKE") == "1")
        fun fixture(name: String) = Path.of(requireNotNull(System.getenv(name)) { "$name is required" })
        val backend = requireNotNull(System.getenv("HEARTH_GPU_BACKEND")) { "Choose HEARTH_GPU_BACKEND=metal or cuda explicitly" }
        val directory = Files.createTempDirectory("hearth-real-gpu-")
        val receipt = System.getenv("HEARTH_GPU_RECEIPT")?.let(Path::of) ?: directory.resolve("receipt.json")
        try {
            // The production validator requires catalog fixture hashes, packaged native resources,
            // real selected-device allocations, and completed native inference counters.
            DesktopGpuValidation.run(backend, fixture("HEARTH_SMOKE_WHISPER"), fixture("HEARTH_SMOKE_TRANSLATION"), fixture("HEARTH_SMOKE_WAV"), receipt)
            val report = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
            assertEquals("PASS", report.getValue("status").jsonPrimitive.content)
            assertEquals(backend, report.getValue("backend").jsonPrimitive.content)
            assertFalse(report.getValue("audioRecorded").jsonPrimitive.boolean)
            assertFalse(report.getValue("audioPlayed").jsonPrimitive.boolean)
            for (stage in listOf("Speech", "Translation")) assertContains(report.getValue("${backend}${stage}Execution").jsonPrimitive.content, "completedInference=1")
        } finally { directory.toFile().deleteRecursively() }
    }
}
