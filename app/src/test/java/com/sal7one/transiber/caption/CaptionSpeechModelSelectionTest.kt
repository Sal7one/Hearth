package com.sal7one.transiber.caption

import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import org.junit.Assert.*
import org.junit.Test

/** Loading and language/readiness provenance must resolve the same selected speech package. */
class CaptionSpeechModelSelectionTest {
    private fun model(id: String, engine: ModelEngineType) = ModelRegistry.RegisteredModel(
        id = id, name = id, engineType = engine, source = ModelRegistry.ModelSource.INTERNAL,
        path = "/unused/$id", sizeBytes = 8, isDirectory = engine == ModelEngineType.VOSK,
        isValid = true, addedTimestamp = 0,
    )

    @Test fun explicitModelUsesItsOwnIdentityEvenWhenItIsNotFirst() {
        for (engine in listOf(ModelEngineType.WHISPER, ModelEngineType.VOSK)) {
            val first = model("first", engine)
            val selected = model("selected", engine)
            val candidates = listOf(first, selected)
            assertSame(selected, selectCaptionSpeechModel(candidates, "selected"))
            assertSame(selected, selectCaptionSpeechModel(candidates.reversed(), "selected"))
        }
    }

    @Test fun missingExplicitModelCannotBorrowAnotherModelsWeightsOrLanguage() {
        for (engine in listOf(ModelEngineType.WHISPER, ModelEngineType.VOSK)) {
            val unrelated = model("another-language", engine)
            assertNull(selectCaptionSpeechModel(listOf(unrelated), "removed-selection"))
            assertNull(selectCaptionSpeechModel(emptyList(), "removed-selection"))
            assertNull("A nonblank ID is exact, not a display-name or trimmed-name match",
                selectCaptionSpeechModel(listOf(unrelated), " another-language "))
        }
    }

    @Test fun unselectedModelRetainsTheExistingFirstAvailableDefault() {
        for (engine in listOf(ModelEngineType.WHISPER, ModelEngineType.VOSK)) {
            val first = model("first", engine)
            val second = model("second", engine)
            for (blank in listOf("", "   ")) assertSame(first, selectCaptionSpeechModel(listOf(first, second), blank))
            assertNull(selectCaptionSpeechModel(emptyList(), ""))
        }
    }
}
