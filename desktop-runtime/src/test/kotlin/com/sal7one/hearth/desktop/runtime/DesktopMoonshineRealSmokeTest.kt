package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineInput
import com.sal7one.hearth.core.SpeechSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Owner opt-in only. Proves the shared sherpa family runs every pinned Moonshine
 * variant through the same [DesktopBackendSpeechEngine] and [DesktopEngines] path.
 * No new engine code.
 *
 * Single-model (historical) fixture:
 * ```
 * HEARTH_MOONSHINE_SMOKE=1 HEARTH_SMOKE_MOONSHINE=<extracted tiny.en root> \
 *   HEARTH_SMOKE_WAV=<16 kHz wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMoonshineRealSmokeTest*"
 * ```
 *
 * All-variant fixture: `HEARTH_SMOKE_MOONSHINE_VARIANTS` points at a directory with
 * one subdirectory per card id (the four pinned files) and one 16 kHz WAV named
 * `<card id>.wav`:
 * ```
 * HEARTH_MOONSHINE_VARIANTS_SMOKE=1 HEARTH_SMOKE_MOONSHINE_VARIANTS=<dir> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopMoonshineRealSmokeTest*"
 * ```
 */
class DesktopMoonshineRealSmokeTest {
    private fun fixture(): Pair<Path, Path> {
        assumeTrue("Set HEARTH_MOONSHINE_SMOKE=1 and provide HEARTH_SMOKE_MOONSHINE and HEARTH_SMOKE_WAV", System.getenv("HEARTH_MOONSHINE_SMOKE") == "1")
        val root = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MOONSHINE")) { "HEARTH_SMOKE_MOONSHINE is required" }).toRealPath()
        val wav = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_WAV")) { "HEARTH_SMOKE_WAV is required" }).toRealPath()
        return root to wav
    }

    private fun variantsFixture(): Path {
        assumeTrue(
            "Set HEARTH_MOONSHINE_VARIANTS_SMOKE=1 and provide HEARTH_SMOKE_MOONSHINE_VARIANTS",
            System.getenv("HEARTH_MOONSHINE_VARIANTS_SMOKE") == "1",
        )
        return Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_MOONSHINE_VARIANTS")) { "HEARTH_SMOKE_MOONSHINE_VARIANTS is required" }).toRealPath()
    }

    @Test
    fun sharedSherpaBridgeTranscribesMoonshine() {
        val (root, wav) = fixture()
        val samples = readPcm16Mono(wav)
        val started = System.nanoTime()
        DesktopBackendSpeechEngine.open(
            "hearth_desktop_qwen3_asr", "moonshine", root.resolve("tokens.txt"), "en", 4,
            entrySymbol = "hearth_moonshine_backend_v1",
            encoder = root.resolve("encoder_model.ort"),
            decoder = root.resolve("decoder_model_merged.ort"),
        ).use { engine ->
            val loadMs = (System.nanoTime() - started) / 1_000_000
            val inferStarted = System.nanoTime()
            val text = engine.transcribe(samples)
            val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
            println("MOONSHINE_SMOKE execution=${engine.executionInfo} loadMs=$loadMs inferMs=$inferMs text=$text")
            assertTrue(text.isNotBlank(), "Moonshine returned no text")
            assertContains(text.lowercase(), "museum")
            assertContains(text.lowercase(), "entrance")
        }
    }

    @Test
    fun desktopEnginePathUsesTheSameBackend() = runBlocking<Unit> {
        val (root, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-moonshine-smoke-")
        val selection = EngineSelection("moonshine-local", "moonshine-tiny-en-v2", root.toString())
        val segments = mutableListOf<SpeechSegment>()
        val started = System.nanoTime()
        try {
            DesktopEngines(CredentialResolver { error("Local Moonshine smoke must never resolve cloud credentials") }, directory).use { engines ->
                assertTrue(engines.capabilities().single { it.id == "moonshine-local" }.available)
                engines.recognize(PipelineInput.AudioFile(wav.toString()), selection, "en") { segments += it }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val text = segments.joinToString(" ") { it.text }
        println("MOONSHINE_ENGINE_SMOKE ms=$elapsed segments=${segments.size} text=$text")
        assertTrue(text.isNotBlank(), "The desktop engine path returned no text")
        assertContains(text.lowercase(), "museum")
        assertContains(text.lowercase(), "entrance")
    }

    /** The desktop engine must verify the selected card's pins, not the old tiny.en constants. */
    @Test
    fun desktopEngineRejectsATamperedCardComponent() = runBlocking<Unit> {
        val (sourceRoot, wav) = fixture()
        val directory = Files.createTempDirectory("hearth-moonshine-tamper-")
        val model = directory.resolve("moonshine-tiny-en-v2")
        try {
            Files.createDirectories(model)
            for (file in MoonshineSpeechModel.variant("moonshine-tiny-en-v2").files) {
                Files.copy(sourceRoot.resolve(file.path), model.resolve(file.path))
            }
            // Same size, different content: only the card's component SHA-256 can catch this.
            val tampered = Files.readAllBytes(model.resolve("tokens.txt"))
            tampered[0] = (tampered[0] + 1).toByte()
            Files.write(model.resolve("tokens.txt"), tampered)
            DesktopEngines(CredentialResolver { error("Local Moonshine smoke must never resolve cloud credentials") },
                directory.resolve("data")).use { engines ->
                val failure = assertIs<IllegalArgumentException>(
                    runCatching {
                        engines.recognize(PipelineInput.AudioFile(wav.toString()),
                            EngineSelection(MoonshineSpeechModel.ENGINE_ID, "moonshine-tiny-en-v2", model.toString()), "auto") { }
                    }.exceptionOrNull(),
                )
                println("MOONSHINE_TAMPER_REJECTED ${failure.message}")
                assertContains(failure.message.orEmpty(), "checksum mismatch")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Runs real speech through every downloaded variant. Present-but-incomplete
     * fixtures fail; absent variants are reported by id and left untested rather
     * than asserted. Transcripts are printed exactly as the model returned them.
     */
    @Test
    fun everyDownloadedVariantProducesATranscript() = runBlocking<Unit> {
        val directory = variantsFixture()
        val engineDirectory = Files.createTempDirectory("hearth-moonshine-variants-")
        var tested = 0
        try {
            DesktopEngines(CredentialResolver { error("Local Moonshine smoke must never resolve cloud credentials") }, engineDirectory).use { engines ->
                val capability = engines.capabilities().single { it.id == MoonshineSpeechModel.ENGINE_ID }
                assertTrue(capability.available, capability.unavailableReason.orEmpty())
                assertEquals(MoonshineSpeechModel.languages, capability.languages)
                for (variant in MoonshineSpeechModel.variants) {
                    val root = directory.resolve(variant.id)
                    val wav = directory.resolve("${variant.id}.wav")
                    if (!Files.isDirectory(root) && !Files.isRegularFile(wav)) {
                        println("MOONSHINE_VARIANT_SKIP id=${variant.id} reason=no-fixture")
                        continue
                    }
                    check(Files.isDirectory(root)) { "Fixture is missing ${variant.id}/" }
                    check(Files.isRegularFile(wav)) { "Fixture is missing ${variant.id}.wav" }
                    val samples = readPcm16Mono(wav)
                    val seconds = samples.size / 16_000.0
                    val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
                    assertTrue(seconds >= 0.5, "${variant.id}: fixture audio is too short ($seconds s)")
                    assertTrue(rms >= 0.003, "${variant.id}: fixture audio is silent (RMS $rms)")

                    val directStarted = System.nanoTime()
                    val direct = DesktopBackendSpeechEngine.open(
                        MoonshineSpeechModel.LIBRARY, MoonshineSpeechModel.BACKEND_ID, root.resolve("tokens.txt"),
                        variant.language, 4,
                        entrySymbol = MoonshineSpeechModel.ENTRY_SYMBOL,
                        encoder = root.resolve("encoder_model.ort"),
                        decoder = root.resolve("decoder_model_merged.ort"),
                    ).use { engine ->
                        val loadMs = (System.nanoTime() - directStarted) / 1_000_000
                        val inferStarted = System.nanoTime()
                        val text = engine.transcribe(samples)
                        val inferMs = (System.nanoTime() - inferStarted) / 1_000_000
                        println("MOONSHINE_VARIANT_BRIDGE id=${variant.id} language=${variant.language} rms=$rms loadMs=$loadMs inferMs=$inferMs text=$text")
                        assertEquals(variant.language, engine.language())
                        text
                    }
                    assertTranscript(variant, direct)

                    val engineStarted = System.nanoTime()
                    val segments = mutableListOf<SpeechSegment>()
                    engines.recognize(PipelineInput.AudioFile(wav.toString()),
                        EngineSelection(MoonshineSpeechModel.ENGINE_ID, variant.id, root.toString()), "auto") { segments += it }
                    val engineMs = (System.nanoTime() - engineStarted) / 1_000_000
                    val text = segments.joinToString(" ") { it.text }
                    println("MOONSHINE_VARIANT_ENGINE id=${variant.id} ms=$engineMs segments=${segments.size} text=$text")
                    assertTranscript(variant, text)
                    assertEquals(variant.language, segments.single().language)
                    tested++
                }
            }
        } finally {
            engineDirectory.toFile().deleteRecursively()
        }
        println("MOONSHINE_VARIANTS_TESTED $tested/${MoonshineSpeechModel.variants.size}")
        assertTrue(tested > 0, "No Moonshine variants were tested")
    }

    private fun assertTranscript(variant: MoonshineSpeechModel.Variant, text: String) {
        assertTrue(text.isNotBlank(), "${variant.id} returned no text")
        assertTrue(text.count { it.isLetter() } >= 4, "${variant.id} returned a non-transcript: $text")
        if (variant.language == "en") {
            assertContains(text.lowercase(), "museum", message = "${variant.id}: $text")
            assertContains(text.lowercase(), "entrance", message = "${variant.id}: $text")
        }
    }
}
