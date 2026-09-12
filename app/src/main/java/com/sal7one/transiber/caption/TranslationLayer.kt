package com.sal7one.transiber.caption

import android.util.Log
import com.sal7one.common_jni.marian.MarianTranslatorEngine
import java.io.File

private const val TAG = "TranslationLayer"

/** A language code rather than a closed enum: new model directions need no UI changes. */
@ConsistentCopyVisibility
data class TranslationTarget private constructor(val languageTag: String) {
    val label get() = com.sal7one.common_jni.language.LanguageCatalog.option(languageTag).label
    val rtl get() = com.sal7one.common_jni.language.LanguageCatalog.option(languageTag).rtl
    // Preserve the original three preference values for existing installs.
    val name get() = when (languageTag) { "en" -> "ENGLISH"; "ar" -> "ARABIC"; "zh" -> "CHINESE"; else -> languageTag }
    companion object {
        val ENGLISH = TranslationTarget("en")
        val ARABIC = TranslationTarget("ar")
        val CHINESE = TranslationTarget("zh")
        val entries get() = com.sal7one.common_jni.translation.TranslationLanguages.hyLanguages.map(::of)
        fun of(code: String): TranslationTarget {
            require(code.matches(Regex("[a-z]{2,3}")) && code !in setOf("auto", "und", "mul")) { "Invalid translation language: $code" }
            return TranslationTarget(code)
        }
        fun fromStored(value: String): TranslationTarget = when(value) {
            "ENGLISH" -> ENGLISH; "ARABIC" -> ARABIC; "CHINESE" -> CHINESE
            else -> runCatching { of(value) }.getOrDefault(ENGLISH)
        }
    }
}

/** What the overlay shows per utterance. */
enum class CaptionDisplay(val label: String) {
    ORIGINAL("Original only"),
    TRANSLATED("Translation only"),
    BOTH("Original + translation"),
}

/**
 * A second-stage machine-translation backend over finalized caption text.
 *
 * Implementations must be safe to call from any thread and must fail closed:
 * an unavailable backend returns a descriptive [TranslationResult.Unavailable]
 * instead of throwing, so the caption pipeline can keep rendering original
 * text while surfacing the reason.
 */
interface Translator {
    /** True when this backend can currently serve [target]. */
    fun isAvailable(target: TranslationTarget): Boolean

    /**
     * Human-readable explanation shown in the overlay when [isAvailable] is
     * false — must tell the user exactly what to do next.
     */
    fun unavailabilityReason(target: TranslationTarget): String

    /** Translates one finalized utterance. Blocking; call off Main. */
    fun translate(text: String, target: TranslationTarget): TranslationResult
}

sealed interface TranslationResult {
    /** Successfully translated text. */
    data class Translated(val text: String) : TranslationResult

    /**
     * The backend cannot serve this request right now. [reason] is shown to
     * the user; original text continues to be displayed.
     */
    data class Unavailable(val reason: String) : TranslationResult
}

/**
 * Serves ENGLISH via Whisper's translation task — no second model needed.
 *
 * This backend is special: the translation happens inside the STT engine
 * itself (SttConfig.translateToEnglish), so the "translator" here is only
 * consulted to explain availability and is never asked to translate text;
 * the engine's output is already English.
 */
class WhisperTaskTranslator : Translator {
    override fun isAvailable(target: TranslationTarget) = target == TranslationTarget.ENGLISH

    override fun unavailabilityReason(target: TranslationTarget): String =
        if (target == TranslationTarget.ENGLISH) {
            "Available"
        } else {
            "Whisper translates only into English. Choose the on-device translation model " +
                "path for ${target.label}."
        }

    override fun translate(text: String, target: TranslationTarget): TranslationResult =
        // Whisper-task translation is applied during transcription; reaching
        // this call means a wiring mistake upstream, so fail loudly-honestly.
        TranslationResult.Unavailable(
            "Whisper task translation happens during transcription; text translation was " +
                "routed here by mistake.",
        )
}

/**
 * Second-stage MT over the bundled ONNX Runtime (quantized OPUS-MT/Marian).
 *
 * The native engine loads tokenizer + encoder + merged decoder from a
 * translation model directory and runs greedy decoding on a dedicated
 * dispatcher. Availability is honest: it requires the runtime to be compiled
 * in AND a registered TRANSLATE model whose directory passes native load.
 *
 * [modelDirectoryProvider] returns the registered translation model
 * directory (or null when none is imported). It is evaluated lazily on every
 * availability check, so imports/deletes take effect without a restart.
 *
 * Call [release] when the caption session stops; the native engine holds
 * ~120 MB of model memory. Release is safe against in-flight translations:
 * the JNI bridge serializes translate and release.
 */
class OnnxMarianTranslator(
    private val modelDirectoryProvider: () -> File? = { null },
) : Translator {

    private val lock = Any()
    private var engine: MarianTranslatorEngine? = null
    private var engineModelPath: String? = null
    private var lastUnavailability: String? = null

    override fun isAvailable(target: TranslationTarget): Boolean {
        if (target != TranslationTarget.ARABIC) {
            lastUnavailability = "On-device translation serves Arabic only."
            return false
        }
        if (!MarianTranslatorEngine.isRuntimeAvailable) {
            lastUnavailability =
                "This build has no compiled on-device translation runtime, so an imported " +
                    "translation model cannot run here. English translation via Whisper works today."
            return false
        }
        val directory = modelDirectoryProvider()
        if (directory == null || !directory.isDirectory) {
            lastUnavailability =
                "Import the English → Arabic translation model first (Model catalogue → " +
                    "Live captions → OPUS-MT)."
            return false
        }
        return engineFor(directory) != null
    }

    override fun unavailabilityReason(target: TranslationTarget): String =
        lastUnavailability ?: "On-device translation into ${target.label} is unavailable."

    override fun translate(text: String, target: TranslationTarget): TranslationResult {
        if (target != TranslationTarget.ARABIC) {
            return TranslationResult.Unavailable(unavailabilityReason(target))
        }
        val directory = modelDirectoryProvider()
        val active = directory?.let { engineFor(it) }
            ?: return TranslationResult.Unavailable(
                isAvailable(target).let { unavailabilityReason(target) },
            )
        return when (val outcome = active.translate(text)) {
            is MarianTranslatorEngine.Outcome.Translated -> {
                Log.i(TAG, "Native translate took ${outcome.latencyMs} ms for ${text.length} chars")
                TranslationResult.Translated(outcome.text)
            }

            is MarianTranslatorEngine.Outcome.Failure ->
                TranslationResult.Unavailable(outcome.reason)
        }
    }

    /**
     * Releases the native engine. The next availability check lazily
     * recreates it, so an active caption session keeps working after a
     * release (at the cost of one reload).
     */
    fun release() {
        synchronized(lock) {
            engine?.close()
            engine = null
            engineModelPath = null
        }
    }

    private fun engineFor(directory: File): MarianTranslatorEngine? {
        val path = directory.canonicalPath
        synchronized(lock) {
            if (engine != null && engineModelPath == path) return engine
            // A different model is registered now: swap engines.
            engine?.close()
            engine = null
            engineModelPath = null
            val created = runCatching {
                MarianTranslatorEngine.create(directory.absolutePath)
            }.getOrNull()
            if (created == null) {
                lastUnavailability =
                    "The imported translation model could not be loaded (" +
                        "${MarianTranslatorEngine.lastError()}). Re-import the model folder " +
                        "with source.spm, tokenizer.json, encoder_model_quantized.onnx and " +
                        "decoder_model_merged_quantized.onnx."
                return null
            }
            engine = created
            engineModelPath = path
            lastUnavailability = null
            return created
        }
    }
}

/**
 * Routes utterances to the right backend. The first backend that supports
 * the target wins; if none does, every call fails closed with the most
 * actionable explanation so the overlay can render one consistent status.
 */
class TranslationLayer(
    private val backends: List<Translator> =
        listOf(WhisperTaskTranslator(), OnnxMarianTranslator()),
) {

    fun isAvailable(target: TranslationTarget): Boolean =
        backends.any { it.isAvailable(target) }

    fun unavailabilityReason(target: TranslationTarget): String =
        backends.firstOrNull { !it.isAvailable(target) }?.unavailabilityReason(target)
            ?: "No translation backend can serve ${target.label}."

    fun translate(text: String, target: TranslationTarget): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult.Unavailable("Nothing to translate.")
        val backend = backends.firstOrNull { it.isAvailable(target) }
            ?: return TranslationResult.Unavailable(unavailabilityReason(target))
        return runCatching { backend.translate(trimmed, target) }
            .getOrElse { TranslationResult.Unavailable("Translation failed: ${it.message}") }
    }

    companion object {
        /**
         * Whether the STT engine itself should run Whisper's translate task
         * for this target — true only for English, where translation is free.
         */
        fun whisperHandlesTarget(target: TranslationTarget): Boolean =
            target == TranslationTarget.ENGLISH
    }
}
