package com.sal7one.transiber.caption

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual Phonon DataStore values, consumed by the engine, survive removing/reopening the controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w360dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SpeechWindowTuningUiTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val context: Context get() = compose.activity.applicationContext
    private val profile = SpeechProfile.PHONON_2
    private val visible = mutableStateOf(true)
    private val enabled = mutableStateOf(true)
    private val applied = AtomicInteger()
    private var previous: SpeechTuning? = null

    @Before fun prepare() {
        runBlocking {
            UserPreferences.initialize(context)
            previous = SpeechTuningStore.tuning(context, profile.id).first()
            SpeechTuningStore.clear(context, profile.id)
        }
    }

    @After fun restore() {
        runBlocking {
            previous?.let { SpeechTuningStore.save(context, profile.id, it) }
                ?: SpeechTuningStore.clear(context, profile.id)
        }
    }

    private fun tag(tuning: SpeechTuning) = "speech-timing-${profile.id}-preset-${tuning.maxUtteranceMs}"
    private fun show(settings: SpeechTuningSettings? = null) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (visible.value) SpeechWindowTuningSection(profile, { applied.incrementAndGet() }, enabled.value, settings)
                }
            }
        }
        awaitLoaded()
    }
    private fun awaitLoaded() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("speech-timing-${profile.id}").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(tag(SpeechTuning.STANDARD)).fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
        }
    }
    private fun persisted() = runBlocking { SpeechTuningStore.current(context, profile.id) }
    private fun store(write: suspend (String, SpeechTuning?) -> Unit) = object : SpeechTuningSettings {
        override fun observe(profileId: String) = SpeechTuningStore.tuning(context, profileId)
        override suspend fun save(profileId: String, tuning: SpeechTuning?) = write.invoke(profileId, tuning)
    }

    @Test fun responsiveAndEagerWriteRealPhononPreferencesAndSurviveReopening() {
        val otherProfile = SpeechProfile.MOONSHINE_TINY_EN.id
        val otherBefore = runBlocking { SpeechTuningStore.current(context, otherProfile) }
        show()
        compose.onNodeWithTag(tag(SpeechTuning.STANDARD)).assertIsSelected().performClick()
        compose.runOnIdle { assertEquals("The selected preset must not restart recognition", 0, applied.get()) }
        assertNull(runBlocking { SpeechTuningStore.tuning(context, profile.id).first() })
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).performClick()
        compose.waitUntil(10_000) { applied.get() == 1 }
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).assertIsSelected()
        assertEquals(SpeechTuning.RESPONSIVE, persisted())
        assertEquals(otherBefore, runBlocking { SpeechTuningStore.current(context, otherProfile) })

        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("speech-timing-${profile.id}").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        awaitLoaded()
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).assertIsSelected()
        compose.onNodeWithTag(tag(SpeechTuning.EAGER)).performClick()
        compose.waitUntil(10_000) { applied.get() == 2 }
        assertEquals(SpeechTuning.EAGER, persisted())
        compose.onNodeWithTag(tag(SpeechTuning.STANDARD)).performClick()
        compose.waitUntil(10_000) { applied.get() == 3 }
        assertNull(runBlocking { SpeechTuningStore.tuning(context, profile.id).first() })
    }

    @Test fun disabledControlsCannotChangeTheSavedPreset() {
        show()
        compose.runOnIdle { enabled.value = false }
        SpeechTuning.PRESETS.forEach { preset -> compose.onNodeWithTag(tag(preset)).assertIsNotEnabled() }
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).performClick()
        compose.runOnIdle { assertEquals(0, applied.get()) }
        assertEquals(SpeechTuning.STANDARD, persisted())
    }

    @Test fun pendingWriteDisablesPresetsAndFailedWriteShowsRealErrorWithoutRestart() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failing = store { _, _ -> entered.complete(Unit); release.await(); throw IOException("Storage is full") }
        show(failing)
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).performClick()
        compose.waitUntil(10_000) { entered.isCompleted }
        SpeechTuning.PRESETS.forEach { preset -> compose.onNodeWithTag(tag(preset)).assertIsNotEnabled() }
        release.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("speech-timing-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("speech-timing-error").assertTextEquals("Storage is full")
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).assertIsEnabled()
        compose.onNodeWithTag(tag(SpeechTuning.STANDARD)).assertIsSelected()
        assertEquals(SpeechTuning.STANDARD, persisted())
        assertEquals(0, applied.get())
    }

    @Test fun removingControlsCancelsUnfinishedWriteWithoutApplyingOrSavingIt() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val waiting = store { _, _ ->
            entered.complete(Unit)
            try { wait.await() } catch (failure: CancellationException) { cancelled.complete(Unit); throw failure }
        }
        show(waiting)
        compose.onNodeWithTag(tag(SpeechTuning.EAGER)).performClick()
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.runOnIdle { visible.value = false }
        // A state write only requests a composition frame. Force removal and disposal before
        // waiting for the suspended writer; polling a Deferred alone does not drain the host UI.
        compose.onNodeWithTag("speech-timing-${profile.id}").assertDoesNotExist()
        compose.waitForIdle()
        compose.waitUntil(10_000) { cancelled.isCompleted }
        assertEquals(0, applied.get())
        assertEquals(SpeechTuning.STANDARD, persisted())
    }
}
