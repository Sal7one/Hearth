package com.sal7one.transiber.caption

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.UiLocaleProvider
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.SetupModeStore
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise actual entry points and the real per-profile store, without opening a native speech model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w500dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CaptionSpeechTimingAccessTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val context: Context get() = compose.activity
    private val profile = SpeechProfile.PHONON_2
    private val visible = mutableStateOf(true)
    private lateinit var previousConfig: CaptionOverlayConfig
    private lateinit var previousSetupMode: SetupMode
    private var previousTuning: SpeechTuning? = null
    private lateinit var speechRoot: File
    private lateinit var speechId: String
    private var composed = false

    @Before fun prepare() = runBlocking {
        UserPreferences.initialize(context)
        previousConfig = CaptionConfigStore.config(context).first()
        previousSetupMode = SetupModeStore.current(context)
        previousTuning = SpeechTuningStore.tuning(context, profile.id).first()
        SpeechTuningStore.clear(context, profile.id)
        SetupModeStore.setMode(context, SetupMode.BASIC)
        assertFalse("This host fixture must never replace a running overlay", CaptionCaptureService.running.value)
        speechId = "speech-${UUID.randomUUID()}"
        speechRoot = File(context.filesDir, "speech-models/$speechId").apply { check(mkdirs()) }
        // Discovery resolves only this manifest. Actual inference would reject the fixture because no weights exist.
        File(speechRoot, SpeechModelPackage.MANIFEST).writeText(JSONObject()
            .put("schemaVersion", 1).put("profile", profile.id).toString())
    }

    @After fun restore() {
        if (composed) compose.runOnIdle { visible.value = false }
        runBlocking {
            if (::previousConfig.isInitialized) CaptionConfigStore.update(context) { previousConfig }
            previousTuning?.let { SpeechTuningStore.save(context, profile.id, it) }
                ?: SpeechTuningStore.clear(context, profile.id)
        }
        if (::previousSetupMode.isInitialized) SetupModeStore.setMode(context, previousSetupMode)
        if (::speechRoot.isInitialized) speechRoot.deleteRecursively()
    }

    @Test fun floatingOverlayGearAndCaptionsTabExposeEagerForTheExactPhononProfile() {
        val config = MutableStateFlow(selected())
        val engine = MutableStateFlow(CaptionEngineController.State(status = CaptionEngineController.Status.RUNNING,
            history = listOf(CaptionLine("Spoken words remain unchanged"))))
        val timingChanges = AtomicInteger()
        val otherBefore = runBlocking { SpeechTuningStore.current(context, SpeechProfile.MOONSHINE_TINY_EN.id) }
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                if (visible.value) CaptionOverlayWindow(config, engine, { transform ->
                    val before = config.value
                    val after = transform(before)
                    if (after.speechSelectionRevision != before.speechSelectionRevision) timingChanges.incrementAndGet()
                    config.value = after
                }, onDrag = { _, _ -> }, onClose = {}, onClear = {}, availableHeightDp = 1000f)
            } }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_caption_settings_da0c8)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.ui_cc_translation_508d4)).assertIsDisplayed().performClick()
        awaitPreset()
        val before = config.value
        compose.onNodeWithTag(tag(SpeechTuning.EAGER)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { timingChanges.get() == 1 && persisted() == SpeechTuning.EAGER }
        compose.onNodeWithTag(tag(SpeechTuning.EAGER)).assertIsSelected()
        compose.runOnIdle {
            assertEquals("Timing may restart speech once; it must preserve model, translation and appearance", before.copy(
                speechSelectionRevision = before.speechSelectionRevision + 1), config.value)
            assertEquals(1, timingChanges.get())
            assertEquals("Spoken words remain unchanged", engine.value.history.single().original)
        }
        assertEquals(otherBefore, runBlocking { SpeechTuningStore.current(context, SpeechProfile.MOONSHINE_TINY_EN.id) })
    }

    @Test fun basicHomeHasDirectResponsiveTimingWithoutOpeningAdvancedSetup() {
        runBlocking { CaptionConfigStore.update(context) { selected() } }
        val before = runBlocking { CaptionConfigStore.config(context).first() }
        val otherBefore = runBlocking { SpeechTuningStore.current(context, SpeechProfile.QWEN3_ASR_0_6B.id) }
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                if (visible.value) CaptionHome({}, {}, {})
            } }
        }
        awaitPreset()
        assertEquals(SetupMode.BASIC, SetupModeStore.current(context))
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            persisted() == SpeechTuning.RESPONSIVE && runBlocking {
                CaptionConfigStore.config(context).first().speechSelectionRevision == before.speechSelectionRevision + 1
            }
        }
        compose.onNodeWithTag(tag(SpeechTuning.RESPONSIVE)).assertIsSelected()
        compose.runOnIdle {
            assertEquals(before.copy(speechSelectionRevision = before.speechSelectionRevision + 1),
                runBlocking { CaptionConfigStore.config(context).first() })
        }
        assertEquals(otherBefore, runBlocking { SpeechTuningStore.current(context, SpeechProfile.QWEN3_ASR_0_6B.id) })
        assertEquals(SetupMode.BASIC, SetupModeStore.current(context))
    }

    private fun selected() = CaptionOverlayConfig(engine = CaptionEngineChoice.PHONON, modelId = speechId,
        source = CaptionSource.MIC, mode = CaptionMode.CAPTIONS, streamLanguage = "en",
        speechSelectionRevision = 37, target = TranslationTarget.ARABIC, localTranslationEnabled = false,
        textTranslationProviderId = "local", localTranslationModelId = "ct2-small100-int8",
        theme = CaptionTheme.HIGH_CONTRAST, widthPercent = 76, bubbleHeightDp = 222, backgroundOpacity = 64)

    private fun tag(tuning: SpeechTuning) = "speech-timing-${profile.id}-preset-${tuning.maxUtteranceMs}"
    private fun persisted() = runBlocking { SpeechTuningStore.current(context, profile.id) }
    private fun awaitPreset() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag(SpeechTuning.STANDARD)).fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            }
        }
    }
}
