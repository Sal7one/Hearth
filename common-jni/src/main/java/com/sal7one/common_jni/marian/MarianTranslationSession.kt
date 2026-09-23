package com.sal7one.common_jni.marian

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One verified ONNX language pair behind the shared caption/text translator contract. */
class MarianTranslationSession private constructor(
    private val engine: MarianTranslatorEngine,
    override val id: String,
    direction: TranslationDirection,
) : CancellableTextTranslator {
    override val directions = setOf(direction)

    override suspend fun translate(text: String, direction: TranslationDirection): String = withContext(Dispatchers.IO) {
        require(direction in directions) { "$id does not support ${direction.source} → ${direction.target}" }
        when (val output = engine.translate(text)) {
            is MarianTranslatorEngine.Outcome.Translated -> output.text
            is MarianTranslatorEngine.Outcome.Failure -> error(output.reason)
        }
    }

    override fun cancel() = engine.cancel()
    override fun close() = engine.close()

    companion object {
        fun open(directory: File, direction: TranslationDirection, label: String): MarianTranslationSession {
            require(directory.isDirectory) { "Marian model folder is missing" }
            val engine = MarianTranslatorEngine.create(directory.absolutePath)
                ?: error(MarianTranslatorEngine.lastError() ?: "Marian translator could not be created")
            return MarianTranslationSession(engine, label, direction)
        }
    }
}
