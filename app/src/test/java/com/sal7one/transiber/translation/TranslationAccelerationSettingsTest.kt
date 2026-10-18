package com.sal7one.transiber.translation

import android.app.Application
import com.sal7one.common_jni.translation.TranslationAcceleration
import com.sal7one.common_jni.translation.TranslationCatalog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TranslationAccelerationSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { com.sal7one.transiber.preferences.UserPreferences.store(context, "conversation-translators").edit().clear().commit() }
    @Test fun existingInstallationsStayOnCpuAndPreferencePersistsIndependently() {
        assertEquals(TranslationAcceleration.CPU, ConversationTranslationSettings.acceleration(context))
        val model = TranslationCatalog.models.first().id
        ConversationTranslationSettings.select(context, ConversationTranslationSettings.LOCAL, model)
        val revision = ConversationTranslationSettings.revision.value
        ConversationTranslationSettings.setAcceleration(context, TranslationAcceleration.VULKAN)
        assertEquals("vulkan", com.sal7one.transiber.preferences.UserPreferences.store(context, "conversation-translators").getString("gguf-acceleration", null))
        assertEquals(model, ConversationTranslationSettings.localModel(context, ""))
        assertEquals(ConversationTranslationSettings.LOCAL, ConversationTranslationSettings.selected(context))
        assertTrue(ConversationTranslationSettings.revision.value > revision)
    }
    @Test fun snapshotFreezesExecutionPolicyAndCompactEnginesStayOnTheirOwnRuntime() {
        val model = TranslationCatalog.models.first().id
        val original = ConversationTranslationSettings.snapshot(context, model, "local")
        ConversationTranslationSettings.setAcceleration(context, TranslationAcceleration.VULKAN)
        val gpu = ConversationTranslationSettings.snapshot(context, model, "local")
        assertEquals(TranslationAcceleration.CPU, original.acceleration)
        assertEquals(TranslationAcceleration.VULKAN, gpu.acceleration)
        assertNotEquals(original, gpu)
        assertEquals(TranslationAcceleration.CPU, ConversationTranslationSettings.snapshot(context, TranslationOptions.MARIAN_EN_AR, "local").acceleration)
        assertEquals(TranslationAcceleration.CPU, ConversationTranslationSettings.snapshot(context, TranslationOptions.ML_KIT, "local").acceleration)
        ConversationTranslationSettings.setAcceleration(context, TranslationAcceleration.CPU)
        assertEquals(TranslationAcceleration.VULKAN, gpu.acceleration)
    }
    @Test fun unknownStoredBackendCannotSilentlyEnableGpu() {
        com.sal7one.transiber.preferences.UserPreferences.store(context, "conversation-translators").edit().putString("gguf-acceleration", "auto").commit()
        assertEquals(TranslationAcceleration.CPU, ConversationTranslationSettings.acceleration(context))
    }
}
