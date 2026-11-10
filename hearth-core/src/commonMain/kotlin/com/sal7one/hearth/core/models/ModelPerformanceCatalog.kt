package com.sal7one.hearth.core.models

enum class PerformancePlatform { ANDROID, MACOS }
enum class PerformanceTask { SPEECH, TRANSLATION, OCR, VOICE }

class ModelMeasurement internal constructor(
    val modelId: String,
    val title: String,
    val elapsedMs: Double,
    val label: String,
    private val scope: MeasurementScope,
) {
    val platform get() = scope.platform
    val task get() = scope.task
    val language get() = scope.language
    val cohort get() = scope.id
    val context get() = "${scope.device} · ${scope.workload} · ${scope.date}"
    val sourceUrl get() = scope.sourceUrl
    val sourceLabel get() = scope.sourceLabel
}

internal data class MeasurementScope(
    val id: String,
    val platform: PerformancePlatform,
    val task: PerformanceTask,
    val language: String?,
    val device: String,
    val workload: String,
    val date: String,
    val sourceUrl: String,
    val sourceLabel: String = "Test details",
) {
    fun measured(id: String, title: String, ms: Double, label: String) =
        ModelMeasurement(id, title, ms, label, this)
}

/** Exact checkpoints and comparable workloads from recorded Hearth runs. No estimates. */
object ModelPerformanceCatalog {
    private const val DOCS = "https://github.com/Sal7one/Hearth/blob/v0.30.3/docs/"
    private const val PHONE = "Galaxy S22 Ultra · CPU · Hearth 0.22.3"
    private const val MAC = "Apple silicon Mac · CPU"
    private const val M4 = "Apple M4 Pro · CPU"
    private const val PHONE_SOURCE = DOCS + "benchmark-phone-smoke-2026-09-23.md"
    private const val DESKTOP_SOURCE = DOCS + "desktop-native-verification.md"
    private const val MANGA_SOURCE = DOCS + "research/koharu-latency.md#production-reader"

    private val androidEnglish = MeasurementScope("s22-fleurs-en", PerformancePlatform.ANDROID, PerformanceTask.SPEECH,
        "en", PHONE, "6 English clips + 2 silence clips, warmed median including resets", "2026-09-23", PHONE_SOURCE)
    private val androidRussian = androidEnglish.copy(id = "s22-fleurs-ru", language = "ru",
        workload = "6 Russian clips + 2 silence clips, forced Russian, warmed median including resets")
    private val androidText = androidEnglish.copy(id = "s22-fleurs-en-ar", task = PerformanceTask.TRANSLATION,
        language = "en→ar", workload = "6 English → Arabic sentences, warmed median")
    private val androidMarian = androidText.copy(id = "s22-marian-en-ar",
        device = "Galaxy S22 Ultra · CPU · historical Marian adapter",
        sourceUrl = "https://github.com/Sal7one/Hearth/blob/68554d8/docs/marian-phone-smoke-2026-09-23.md")
    private val macMoonshine = MeasurementScope("mac-moonshine-en", PerformancePlatform.MACOS, PerformanceTask.SPEECH,
        "en", "$MAC, 4 threads", "native decode of a 4.44 s English clip", "2026-10-22", DESKTOP_SOURCE)
    private val macSherpa = macMoonshine.copy(id = "mac-sherpa-en", workload = "native decode of one English clip")
    private val macReplay = MeasurementScope("m4-fleurs-en-1897-replay", PerformancePlatform.MACOS, PerformanceTask.SPEECH,
        "en", "$M4, 4 threads", "30 warm native replays of the same 7.14 s English clip", "2026-11-10",
        "https://huggingface.co/datasets/google/fleurs/tree/70bb2e84b976b7e960aa89f1c648e09c59f894dd",
        "Test audio source")
    private val macPhononRussian = macSherpa.copy(id = "mac-phonon25-ru", language = "ru", date = "2026-10-24",
        workload = "native decode of a 7.62 s Russian clip", sourceUrl = DOCS + "models/phonon-2.5.md")
    private val macText = MeasurementScope("mac-ct2-en-ar", PerformancePlatform.MACOS, PerformanceTask.TRANSLATION,
        "en→ar", "Apple silicon Mac · CPU Ruy, 2 threads", "3 English → Arabic prompts, cold-process median including model load",
        "2026-10-20", DOCS + "models/ct2-host-smoke-2026-10-20.json")
    private val macManga = MeasurementScope("m4-manga-read", PerformancePlatform.MACOS, PerformanceTask.OCR,
        "ja", M4, "33-line manga page, 20 warm Read runs, source pixels already available", "2026-11-10", MANGA_SOURCE)
    private val macMangaText = macManga.copy(id = "m4-manga-ja-en", task = PerformanceTask.TRANSLATION, language = "ja→en",
        workload = "first translation of a 33-line Japanese → English page, including translator load")
    private val macVoice = MeasurementScope("mac-supertonic-ar-f1", PerformancePlatform.MACOS, PerformanceTask.VOICE,
        "ar", MAC, "one Arabic synthesis with style F1, 4.28 s of generated audio",
        "2026-10-21", DOCS + "research/desktop-supertonic-smoke-2026-10-21.json")

    private val measurements = listOf(
        androidEnglish.measured("moonshine-tiny-en-v2", "Moonshine Tiny English INT8", 1330.0, "1.33 s warm suite"),
        androidEnglish.measured("whisper-tiny-q5_1", "Whisper Tiny multilingual Q5_1", 16430.0, "16.43 s warm suite"),
        androidRussian.measured("qwen3-asr-0.6b", "Qwen3-ASR 0.6B INT8", 17620.0, "17.62 s warm suite"),
        androidRussian.measured("nemotron-3.5-asr-0.6b", "Nemotron 3.5 ASR 0.6B Q8_0", 24100.0, "24.10 s warm suite"),
        androidText.measured("ml-kit", "ML Kit installed packs", 390.0, "390 ms warm suite"),
        androidText.measured("hy-mt2-q4", "Hy-MT2 1.8B Q4_K_M", 53010.0, "53.01 s warm suite"),
        androidMarian.measured("marian-en-ar", "Marian English → Arabic", 1860.0, "1.86 s warm suite"),
        androidMarian.copy(id = "s22-marian-ru-en", language = "ru→en", workload = "6 Russian → English sentences, warmed median")
            .measured("marian-ru-en", "Marian Russian → English", 2290.0, "2.29 s warm suite"),
        androidMarian.copy(id = "s22-marian-zh-en", language = "zh→en", workload = "6 Chinese → English sentences, warmed median")
            .measured("marian-zh-en", "Marian Chinese → English", 1840.0, "1.84 s warm suite"),
        macReplay.measured("moonshine-tiny-en-v2", "Moonshine Tiny English INT8", 35.656, "35.7 ms p50 · 36.4 ms p95"),
        macReplay.measured("phonon-2", "Phonon 2 English INT8", 164.555, "164.6 ms p50 · 167.5 ms p95"),
        macReplay.measured("phonon-2.5", "Phonon 2.5 INT8", 164.866, "164.9 ms p50 · 166.7 ms p95"),
        macMoonshine.measured("moonshine-base-en-v2", "Moonshine Base English INT8", 58.0, "58 ms decode"),
        macSherpa.measured("omnilingual-ctc-300m-v2-int8", "Omnilingual CTC 300M INT8", 220.0, "220 ms decode"),
        macPhononRussian.measured("phonon-2.5", "Phonon 2.5 INT8", 163.0, "163 ms decode"),
        macText.measured("ct2-small100-int8", "SMaLL-100 INT8", 297.0, "297 ms cold translation"),
        macText.measured("ct2-quickmt-en-ar", "QuickMT English → Arabic", 395.0, "395 ms cold translation"),
        macManga.measured("manga-line-page", "PP-OCRv6 Manga Fast lines", 165.0, "165 ms warm Read (p95)"),
        macMangaText.measured("ct2-quickmt-ja-en-v2", "QuickMT v2 Japanese → English", 2621.0, "2.62 s first full translation"),
        macVoice.measured("supertonic-3", "Supertonic 3 · F1", 506.0, "506 ms synthesis"),
    )

    fun forPlatform(platform: PerformancePlatform): List<ModelMeasurement> =
        measurements.filter { it.platform == platform }

    fun forModel(modelId: String, platform: PerformancePlatform): List<ModelMeasurement> =
        measurements.filter { it.modelId == modelId && it.platform == platform }
}
