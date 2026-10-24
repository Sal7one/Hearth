package com.sal7one.transiber.caption

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.CloudConfigStore
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exact manifest identities only: a native model open would reject these deliberately weightless fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CaptionSpeechModelPickerUiTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val context: Context get() = compose.activity
    private val visible = mutableStateOf(true)
    private val enabled = mutableStateOf(true)
    private val config = mutableStateOf(CaptionOverlayConfig())
    private val changes = AtomicInteger()
    private val manages = AtomicInteger()
    private val fixtures = mutableListOf<File>()
    private lateinit var english: File
    private lateinit var multilingual: File
    private lateinit var owner: Owner
    private var composed = false
    private var tvFeatureBefore: Boolean? = null
    private var previousConfig: CaptionOverlayConfig? = null
    private var previousSetupMode: SetupMode? = null

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepare() {
        runBlocking { UserPreferences.initialize(context) }
        compose.runOnUiThread { owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED } }
        english = fixture(SpeechProfile.PHONON_2)
        multilingual = fixture(SpeechProfile.PHONON_2_5)
        fixture(SpeechProfile.QWEN3_ASR_0_6B)
        config.value = selected(english.name)
    }

    @After fun restore() {
        if (composed) compose.runOnIdle { visible.value = false }
        previousConfig?.let { saved -> runBlocking { CaptionConfigStore.update(context) { saved } } }
        previousSetupMode?.let { SetupModeStore.setMode(context, it) }
        fixtures.forEach { it.deleteRecursively() }
        tvFeatureBefore?.let { shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEVISION, it) }
    }

    @Test fun selectingActualPhonon25PreservesEveryUnrelatedPreferenceAndSameIdIsNoOp() {
        show()
        awaitLabel(english)
        val before = config.value
        compose.onNodeWithTag("caption-speech-model-menu").performClick()
        compose.onNodeWithTag(row(english)).assertIsSelected()
        compose.onNodeWithText("Qwen3-ASR 0.6B", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(row(multilingual)).assertIsNotSelected().assertTextContains(label(multilingual)).performClick()
        awaitLabel(multilingual)
        compose.runOnIdle {
            assertEquals(before.copy(modelId = multilingual.name), config.value)
            assertEquals(1, changes.get())
        }
        compose.onNodeWithTag("caption-speech-model-menu").performClick()
        compose.onNodeWithTag(row(multilingual)).assertIsSelected().performClick()
        compose.runOnIdle { assertEquals("Choosing the active package must not restart or rewrite setup", 1, changes.get()) }
        compose.onNodeWithTag("caption-speech-model-manage").performClick()
        compose.runOnIdle { assertEquals(1, manages.get()); assertEquals(before.copy(modelId = multilingual.name), config.value) }
    }

    @Test fun missingExplicitSelectionNeverBorrowsEnglishCheckpointIdentity() {
        val missingId = "speech-${UUID.randomUUID()}"
        config.value = selected(missingId)
        show()
        compose.waitUntil(15_000) { nodesContaining("package is unavailable: $missingId").isNotEmpty() }
        compose.onNodeWithTag("caption-speech-model-menu").assertIsEnabled()
            .assertTextContains(context.getString(R.string.ui_choose_a_model_fefb3))
        compose.onNodeWithText("Phonon 2 · English", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("caption-speech-model-menu").performClick()
        compose.onNodeWithTag(row(english)).assertIsNotSelected()
        compose.onNodeWithTag(row(multilingual)).assertIsNotSelected().performClick()
        awaitLabel(multilingual)
        compose.runOnIdle { assertEquals(multilingual.name, config.value.modelId); assertEquals(1, changes.get()) }
    }

    @Test fun sameIdManifestDamageAndRepairRefreshOnResumeWithoutKeepingStaleLabel() {
        config.value = selected(multilingual.name)
        show()
        awaitLabel(multilingual)
        multilingual.resolve(SpeechModelPackage.MANIFEST).writeText("{")
        resume()
        compose.waitUntil(15_000) { nodesContaining("${multilingual.name}:").isNotEmpty() }
        compose.onNodeWithTag("caption-speech-model-menu").assertTextContains(context.getString(R.string.ui_choose_a_model_fefb3))
        compose.onNodeWithText(label(multilingual)).assertDoesNotExist()
        writeManifest(multilingual, SpeechProfile.PHONON_2_5)
        resume()
        awaitLabel(multilingual)
        compose.runOnIdle { assertEquals(0, changes.get()); assertEquals(multilingual.name, config.value.modelId) }
    }

    @Test fun disabledControlsCannotSelectOrManageModels() {
        enabled.value = false
        show()
        awaitLabel(english)
        compose.onNodeWithTag("caption-speech-model-menu").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("caption-speech-model-manage").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes.get()); assertEquals(0, manages.get()) }
    }

    @Test fun tvCannotExposeOrLoadLocalModelChoices() {
        tvFeatureBefore = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEVISION, true)
        show()
        compose.onNodeWithText(context.getString(R.string.tv_local_models_disabled)).assertIsDisplayed()
        compose.onNodeWithTag("caption-speech-model-menu").assertDoesNotExist()
        compose.onNodeWithTag("caption-speech-model-manage").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes.get()) }
    }

    @Test fun floatingOverlayGearExposesPhonon25AndRefreshesActualLanguageCoverage() {
        val saved = MutableStateFlow(selected(english.name))
        val switches = AtomicInteger()
        val state = MutableStateFlow(CaptionEngineController.State(status = CaptionEngineController.Status.RUNNING,
            history = listOf(CaptionLine("The current caption stays unchanged"))))
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) CaptionOverlayWindow(saved, state, { transform ->
                        val before = saved.value
                        val after = transform(before)
                        if (after.modelId != before.modelId) switches.incrementAndGet()
                        saved.value = after
                    }, onDrag = { _, _ -> }, onClose = {}, onClear = {}, availableHeightDp = 900f)
                }
            } }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.ui_caption_settings_da0c8)).performClick()
        compose.onNodeWithText(context.getString(R.string.ui_cc_translation_508d4)).performClick()
        awaitLabel(english)
        val before = saved.value
        compose.onNodeWithTag("caption-speech-model-menu").performScrollTo().performClick()
        compose.onNodeWithTag(row(multilingual)).performScrollTo().performClick()
        awaitLabel(multilingual)
        val note = context.getString(R.string.model_phonon_25_language_note)
        compose.waitUntil(15_000) { nodesContaining(note).isNotEmpty() }
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(before.copy(modelId = multilingual.name), saved.value)
            assertEquals(1, switches.get())
            assertEquals("The current caption stays unchanged", state.value.history.single().original)
            val model = captionLanguageModel(context, saved.value)
            assertEquals(SpeechProfile.PHONON_2_5.id, model.id)
            val languages = CaptionLanguages.source(saved.value, CloudConfigStore.SttMode.BATCH, model)
            assertEquals(SpeechProfile.PHONON_2_5.capabilities.sourceLanguages + "auto", languages.codes)
            assertEquals(26, languages.codes.size)
            assertTrue(languages.languages.none { it.canForce })
            assertFalse("ar" in languages.codes || "zh" in languages.codes)
        }
    }

    @Test fun homeAdvancedOptionsShowPhononFamilyVersionsAndPersistExactCheckpointChoice() {
        assertFalse("This fixture must never change a running overlay", CaptionCaptureService.running.value)
        previousConfig = runBlocking { CaptionConfigStore.config(context).first() }
        previousSetupMode = SetupModeStore.current(context)
        SetupModeStore.setMode(context, SetupMode.ADVANCED)
        val initial = selected(english.name).copy(mode = CaptionMode.CAPTIONS, localTranslationEnabled = false)
        runBlocking { CaptionConfigStore.update(context) { initial } }
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) CaptionHome({}, {}, {})
                }
            } }
        }
        val openSettings = context.getString(R.string.ui_speech_translation_8002b)
        compose.waitUntil(15_000) { nodesContaining(openSettings).isNotEmpty() }
        compose.onNodeWithText(openSettings).performScrollTo().performClick()
        awaitLabel(english)
        compose.onNodeWithText(context.getString(R.string.label_captionenginechoice_phonon_label)).assertExists()
        compose.onNodeWithTag("caption-speech-model-menu").performScrollTo().performClick()
        compose.onNodeWithTag(row(multilingual)).performScrollTo().performClick()
        awaitLabel(multilingual)
        compose.waitUntil(15_000) {
            runBlocking { CaptionConfigStore.config(context).first().modelId == multilingual.name }
        }
        compose.runOnIdle {
            val saved = runBlocking { CaptionConfigStore.config(context).first() }
            assertEquals(initial.copy(modelId = multilingual.name), saved)
            assertEquals(SpeechProfile.PHONON_2_5.id, captionLanguageModel(context, saved).id)
        }
    }

    private fun show() {
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    Column(Modifier.fillMaxSize()) {
                        if (visible.value) CaptionSpeechModelPicker(config.value, { transform ->
                            changes.incrementAndGet()
                            config.value = transform(config.value)
                        }, { manages.incrementAndGet() }, enabled.value)
                    }
                }
            } }
        }
    }

    private fun fixture(profile: SpeechProfile): File = File(context.filesDir, "speech-models/speech-${UUID.randomUUID()}")
        .also { check(it.mkdirs()); fixtures += it; writeManifest(it, profile) }

    private fun writeManifest(root: File, profile: SpeechProfile) {
        root.resolve(SpeechModelPackage.MANIFEST).writeText(JSONObject().put("schemaVersion", 1).put("profile", profile.id).toString())
    }

    private fun selected(modelId: String) = CaptionOverlayConfig(engine = CaptionEngineChoice.PHONON, modelId = modelId,
        mode = CaptionMode.TRANSLATE, localTranslationEnabled = true, localTranslationModelId = "ct2-small100-int8",
        textTranslationProviderId = "local", streamLanguage = "en", target = TranslationTarget.ARABIC,
        speechSelectionRevision = 19, theme = CaptionTheme.HIGH_CONTRAST, widthPercent = 72,
        backgroundOpacity = 61, bubbleHeightDp = 208, speakerChoice = CaptionSpeakerChoice.SHARED)

    private fun label(root: File) = "${if (root == english) SpeechProfile.PHONON_2.label else SpeechProfile.PHONON_2_5.label} · ${root.name.takeLast(6)}"
    private fun row(root: File) = "caption-speech-model-${root.name}"
    private fun nodesContaining(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
    private fun awaitLabel(root: File) {
        compose.waitUntil(15_000) { nodesContaining(label(root)).isNotEmpty() }
        compose.onNodeWithTag("caption-speech-model-menu").assertTextContains(label(root))
    }
    private fun resume() = compose.runOnUiThread {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
}
