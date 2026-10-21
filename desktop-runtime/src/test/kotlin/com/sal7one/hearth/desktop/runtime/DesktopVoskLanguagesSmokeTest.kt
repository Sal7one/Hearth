package com.sal7one.hearth.desktop.runtime

import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Owner opt-in only: the six pinned Vosk models through the app's shared backend
 * engine, one real utterance per language. Spanish is reported with its transcript;
 * the standalone repeat-stability probe flagged nondeterminism on synthetic audio.
 *
 * ```
 * HEARTH_VOSK_LANGUAGES_SMOKE=1 \
 *   HEARTH_SMOKE_VOSK_MODELS=<dir with all six extracted models> \
 *   HEARTH_SMOKE_VOSK_AUDIO=<dir with en/de/fr/es/ru/zh.wav> \
 *   ./gradlew -PhearthDesktopOnly=true :desktop-runtime:test --tests "*DesktopVoskLanguagesSmokeTest*"
 * ```
 */
class DesktopVoskLanguagesSmokeTest {
    private val models = mapOf(
        "en" to "vosk-model-small-en-us-0.15",
        "de" to "vosk-model-small-de-0.15",
        "fr" to "vosk-model-small-fr-0.22",
        "es" to "vosk-model-small-es-0.42",
        "ru" to "vosk-model-small-ru-0.22",
        "zh" to "vosk-model-small-cn-0.22",
    )

    @Test
    fun everyPinnedVoskLanguageLoadsAndTranscribes() {
        assumeTrue(
            "Set HEARTH_VOSK_LANGUAGES_SMOKE=1 and provide HEARTH_SMOKE_VOSK_MODELS and HEARTH_SMOKE_VOSK_AUDIO",
            System.getenv("HEARTH_VOSK_LANGUAGES_SMOKE") == "1",
        )
        val root = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_VOSK_MODELS"))).toRealPath()
        val audio = Path.of(requireNotNull(System.getenv("HEARTH_SMOKE_VOSK_AUDIO"))).toRealPath()
        val failures = mutableListOf<String>()
        for ((language, directory) in models) {
            val model = root.resolve(directory)
            val wav = audio.resolve("$language.wav")
            try {
                val samples = readPcm16Mono(wav)
                val started = System.nanoTime()
                DesktopBackendSpeechEngine.open("hearth_vosk", "vosk", model, language, 4).use { engine ->
                    val text = engine.transcribe(samples)
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    println("VOSK_LANGUAGE $language ms=$elapsed text=$text")
                    assertTrue(text.isNotBlank(), "$language returned no text")
                }
            } catch (failure: Throwable) {
                failures += "$language: ${failure.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }
}
