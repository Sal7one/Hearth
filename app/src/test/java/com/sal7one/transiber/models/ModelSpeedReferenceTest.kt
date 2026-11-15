package com.sal7one.transiber.models

import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.hearth.core.models.ModelPerformanceCatalog
import com.sal7one.hearth.core.models.PerformancePlatform
import com.sal7one.hearth.core.models.PerformanceTask
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.TranslationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpeedReferenceTest {
    @Test fun everyAndroidReferenceUsesACurrentCheckpoint() {
        val speechIds = SpeechArtifactCatalog.all.map { it.id }.toSet()
        val translationIds = TranslationCatalog.models.map { it.id }.toSet() +
            MarianPackage.pairs.map { it.id } + TranslationOptions.ML_KIT
        ModelPerformanceCatalog.forPlatform(PerformancePlatform.ANDROID).forEach { row ->
            val ids = if (row.task == PerformanceTask.SPEECH) speechIds else translationIds
            assertTrue(row.modelId, row.modelId in ids)
        }
    }

    @Test fun theDisplayedMeasurementMatchesTheChosenLanguageAndDirection() {
        assertEquals(1330.0, modelSpeedReferences("moonshine-tiny-en-v2", "en").single().elapsedMs, 0.0)
        assertTrue(modelSpeedReferences("moonshine-tiny-en-v2", "ar").isEmpty())
        assertEquals(1860.0, modelSpeedReferences("marian-en-ar", "en", "ar").single().elapsedMs, 0.0)
        assertTrue(modelSpeedReferences("marian-en-ar", "ar", "en").isEmpty())
        assertTrue(modelSpeedReferences("phonon-2.5").isEmpty())
    }
}
