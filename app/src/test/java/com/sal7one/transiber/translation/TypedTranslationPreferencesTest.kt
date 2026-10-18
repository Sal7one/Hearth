package com.sal7one.transiber.translation

import android.app.Application
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TypedTranslationPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = UserPreferences.store(context, "typed-translation")

    @Before fun reset() = runBlocking {
        UserPreferences.initialize(context)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun bundleChangesReplaceTheReactivePairWithoutAStaleScreenWriteBack() = runBlocking {
        val observed = mutableListOf<TypedTranslationPreferences>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            TypedTranslationSettings.values(context).take(2).toList(observed)
        }
        // This is the same atomic edit used when applying a portable setup.
        assertTrue(preferences.edit().putString("source", "ru").putString("target", "en").commit())
        observer.join()
        assertEquals(listOf("en" to "ar", "ru" to "en"), observed.map { it.source to it.target })
        assertEquals("ru", TypedTranslationSettings.current(context).source)
        assertEquals("en", TypedTranslationSettings.current(context).target)
    }

    @Test fun pickerAndSwapUseTheLatestPairAndPreserveAutomaticPreference() {
        val oldScreen = TypedTranslationSettings.current(context)
        assertTrue(preferences.edit().putString("source", "de").putString("target", "fr")
            .putBoolean("automatic", false).commit())
        assertEquals("ar", oldScreen.target)
        // A picker opened before the bundle applied must preserve the new target.
        TypedTranslationSettings.source(context, "es")
        assertEquals(TypedTranslationPreferences("es", "fr", false), TypedTranslationSettings.current(context))
        TypedTranslationSettings.swap(context)
        assertEquals(TypedTranslationPreferences("fr", "es", false), TypedTranslationSettings.current(context))
        TypedTranslationSettings.target(context, "ar")
        assertEquals(TypedTranslationPreferences("fr", "ar", false), TypedTranslationSettings.current(context))
    }

    @Test fun sharedManualInputDoesNotChangeTheSavedAutomaticChoice() {
        TypedTranslationSettings.automatic(context, true)
        val shared = TypedInputOrigin.shared(immediate = false)
        assertFalse(shared.automatic(TypedTranslationSettings.current(context).automatic))
        assertEquals(TypedInputOrigin.SHARED_MANUAL, shared.edited("changed shared text"))
        assertTrue(TypedTranslationSettings.current(context).automatic)
        // Clearing the imported payload returns the next typed input to the saved choice.
        assertTrue(shared.edited("").automatic(TypedTranslationSettings.current(context).automatic))
        TypedTranslationSettings.automatic(context, false)
        val processText = TypedInputOrigin.shared(immediate = true)
        assertTrue(processText.automatic(TypedTranslationSettings.current(context).automatic))
        assertFalse(TypedTranslationSettings.current(context).automatic)
        assertFalse(processText.edited("").automatic(TypedTranslationSettings.current(context).automatic))
    }

    @Test fun explicitAutomaticSelectionPersistsAcrossPairAndIncomingBundleUpdates() = runBlocking {
        TypedTranslationSettings.automatic(context, false)
        TypedTranslationSettings.source(context, "ar")
        assertTrue(preferences.edit().putString("source", "fr").putString("target", "en").commit())
        val snapshot = TypedTranslationSettings.values(context).take(1).toList().single()
        assertEquals(TypedTranslationPreferences("fr", "en", false), snapshot)
        TypedTranslationSettings.automatic(context, true)
        assertEquals(TypedTranslationPreferences("fr", "en", true), TypedTranslationSettings.current(context))
    }

    @Test fun sharedManualInputStillAllowsOneCpuRetryAfterTheCpuRevisionArrives() {
        val automatic = TypedInputOrigin.SHARED_MANUAL.automatic(saved = true)
        val policy = TypedTranslationRequestPolicy()
        policy.requestImmediateAfter(7)
        assertEquals(TypedTranslationRequestPolicy.Action.KEEP, policy.takeAction(automatic, 6))
        assertEquals(TypedTranslationRequestPolicy.Action.IMMEDIATE, policy.takeAction(automatic, 7))
        assertEquals(TypedTranslationRequestPolicy.Action.CLEAR, policy.takeAction(automatic, 8))
    }
}
