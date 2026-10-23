package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.caption.label
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real screen discovery, DataStore, private WAV restoration and Run readiness; no inference or weights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LocalBenchmarkScreenStateTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var previousPreferences = emptyMap<String, Any?>()
    private val backups = mutableListOf<Pair<File, File>>()
    private val directories = listOf("speech-models", "benchmark-saudi", "benchmark-custom", "benchmark-saudi-draft")
    private val selectedIds = (0 until 20).map { "speech-${UUID.nameUUIDFromBytes("benchmark-screen-$it".toByteArray())}" }.toSet()
    private val show = mutableStateOf(true)
    private var composed = false

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        UserPreferences.initialize(context)
        preferences = UserPreferences.store(context, "benchmark-options")
        previousPreferences = preferences.all.toMap()
        assertFalse("The fixture must not start or replace a real benchmark", BenchmarkRunController.state.value.running)
        assertNull(LocalWorkGate.owner.value)
        for (name in directories) {
            val directory = File(context.filesDir, name)
            if (directory.exists()) {
                val backup = File(context.filesDir, ".benchmark-ui-backup-${UUID.randomUUID()}")
                Files.move(directory.toPath(), backup.toPath())
                backups += directory to backup
            }
        }
        val root = File(context.filesDir, "speech-models").apply { check(mkdirs()) }
        selectedIds.forEach { id ->
            val directory = File(root, id).apply { check(mkdir()) }
            // Browser discovery intentionally reads only the manifest. A real Run would still
            // reject this fixture at integrity verification before opening a native runtime.
            File(directory, SpeechModelPackage.MANIFEST).writeText(JSONObject()
                .put("schemaVersion", 1).put("profile", SpeechProfile.OWSM_V4_BASE_102M.id).toString())
        }
        check(preferences.edit().clear()
            .putString("mode", "Speech").putString("section", "Models")
            .putString("source", "ar").putString("target", "en")
            .putStringSet("selected-Speech", selectedIds)
            .putBoolean("useBuiltInSet", true).putBoolean("useSaudiSet", true)
            .putBoolean("reference-language-matrix", false).commit())
        show.value = true
        initializeLocalMlKitIfPresent()
    }

    @After fun restore() {
        if (composed) compose.runOnIdle { show.value = false }
        if (::context.isInitialized) {
            directories.forEach { File(context.filesDir, it).deleteRecursively() }
            backups.forEach { (directory, backup) -> Files.move(backup.toPath(), directory.toPath()) }
        }
        if (::preferences.isInitialized) {
            val editor = preferences.edit().clear()
            previousPreferences.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            } }
            check(editor.commit())
        }
    }

    @Test fun twentySelectedModelsExplainMissingSaudiInputAndBundledArabicMakesRunReady() {
        openScreen()
        awaitReason("No Saudi recordings")
        compose.onNodeWithTag("benchmark-compare").assertIsNotEnabled()
        compose.onNodeWithTag("benchmark-use-bundled").assertIsDisplayed()
        compose.runOnIdle { assertEquals(selectedIds, preferences.getStringSet("selected-Speech", null)) }

        // The existing model selection is usable while catalogue labels are mapped separately.
        val first = selectedIds.sorted().first()
        val label = SpeechProfile.OWSM_V4_BASE_102M.label + " · " + first.takeLast(6)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        val modelRow = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        modelRow.assertIsEnabled().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(19, preferences.getStringSet("selected-Speech", null)!!.size) }
        modelRow.performClick()
        compose.runOnIdle { assertEquals(selectedIds, preferences.getStringSet("selected-Speech", null)) }

        // Fixing the input neither downloads nor discards those twenty model choices.
        compose.onNodeWithTag("benchmark-use-bundled").performClick()
        compose.onNodeWithTag("benchmark-run-reason").assertDoesNotExist()
        compose.onNodeWithTag("benchmark-compare").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(selectedIds, preferences.getStringSet("selected-Speech", null))
            assertFalse(preferences.getBoolean("useSaudiSet", true))
        }
    }

    @Test fun customRecordingAndSelectionRestoreAfterLeavingAndReopeningTheScreen() {
        val label = "My Arabic benchmark.wav"
        val recording = BenchmarkAudio.fromPcm16(ShortArray(16_000) { (it % 100).toShort() })
        val saved = BenchmarkCustomAudioStore(File(context.filesDir, "benchmark-custom")).save(recording, label)
        check(preferences.edit().putBoolean("useBuiltInSet", false).putBoolean("useSaudiSet", false).commit())
        openScreen()
        awaitInput(label)
        compose.onNodeWithTag("benchmark-compare").assertIsEnabled()
        compose.runOnIdle { show.value = false }
        compose.onNodeWithTag("benchmark-run-footer").assertDoesNotExist()
        compose.runOnIdle { show.value = true }
        awaitInput(label)
        compose.onNodeWithTag("benchmark-compare").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(selectedIds, preferences.getStringSet("selected-Speech", null))
            assertEquals(saved.clip.sha256, BenchmarkCustomAudioStore(File(context.filesDir, "benchmark-custom")).load()!!.clip.sha256)
        }
    }

    @Test fun deadlineChoicePersistsAndAnOldPreferenceCannotExceedTwentySeconds() {
        check(preferences.edit().putString("section", "Setup").putInt("attempt-timeout-seconds", 999).commit())
        openScreen()
        awaitReason("No Saudi recordings")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("benchmark-time-limit-20"))
        compose.onNodeWithTag("benchmark-time-limit-20").assertIsSelected()
        compose.onNodeWithTag("benchmark-time-limit-5").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(5, preferences.getInt("attempt-timeout-seconds", -1))
            show.value = false
        }
        compose.onNodeWithTag("benchmark-run-footer").assertDoesNotExist()
        compose.runOnIdle { show.value = true }
        awaitReason("No Saudi recordings")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("benchmark-time-limit-5"))
        compose.onNodeWithTag("benchmark-time-limit-5").assertIsSelected()
        compose.onNodeWithTag("benchmark-time-limit-description").assertTextContains("all 3 passes", substring = true)
        compose.onNodeWithTag("benchmark-edit-input").assertTextContains("5s/model limit", substring = true)
    }

    @Test fun actualCustomAudioDurationIsVisibleBesideTheWholeAttemptDeadline() {
        val recording = BenchmarkAudio.fromPcm16(ShortArray(40_000) { (it % 100).toShort() })
        BenchmarkCustomAudioStore(File(context.filesDir, "benchmark-custom")).save(recording, "Two and a half seconds.wav")
        check(preferences.edit().putString("section", "Setup").putBoolean("useBuiltInSet", false)
            .putBoolean("useSaudiSet", false).commit())
        openScreen()
        awaitInput("Two and a half seconds.wav")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("benchmark-audio-duration"))
        compose.onNodeWithTag("benchmark-audio-duration").assertTextContains("2.50 s each", substring = true)
            .assertTextContains("3 passes: first + 2 warm", substring = true)
        compose.onNodeWithTag("benchmark-time-limit-description").assertTextContains("loading", substring = true)
    }

    @Test fun matrixTranslationTargetIgnoresTheHiddenSingleInputSource() {
        check(preferences.edit().putString("mode", "Translation").putString("section", "Setup")
            .putString("source", "ar").putString("target", "en")
            .putBoolean("reference-language-matrix", true)
            .putString("reference-language-codes", "en,ru,zh").commit())
        openScreen()
        awaitReason("Select at least one model")
        compose.onNodeWithText("Translate to: English").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Search languages").performTextInput("Arabic")
        compose.onNode(hasText("Arabic") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsDisplayed().performClick()
        compose.runOnIdle {
            // Arabic is a valid target for the matrix's English/Russian/Chinese sources.
            // The hidden one-language source preference must neither filter it nor change it.
            assertEquals("ar", preferences.getString("target", null))
            assertEquals("ar", preferences.getString("source", null))
        }
        compose.onNodeWithText("Translate to: العربية · Arabic").assertExists()
    }

    @Test fun bundledLanguageActionReplacesEmptyOrStaleMatrixSelections() {
        val action = "Use bundled English / Russian / Chinese tests"
        for ((index, stale) in listOf("", "ar").withIndex()) {
            check(preferences.edit().putString("mode", "Speech").putString("section", "Models")
                .putString("source", "ar").putBoolean("useBuiltInSet", true).putBoolean("useSaudiSet", true)
                .putBoolean("reference-language-matrix", false).putString("reference-language-codes", stale).commit())
            if (index == 0) openScreen() else compose.runOnIdle { show.value = true }
            awaitReason("No Saudi recordings")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(action))
            compose.onNodeWithText(action).assertIsEnabled().performClick()
            compose.onNodeWithTag("benchmark-edit-input").assertTextContains("EN / RU / ZH", substring = true)
            compose.onNodeWithTag("benchmark-compare").assertIsEnabled()
            compose.runOnIdle {
                assertEquals(setOf("en", "ru", "zh"), preferences.getString("reference-language-codes", "")!!.split(',').toSet())
                assertEquals(selectedIds, preferences.getStringSet("selected-Speech", null))
                show.value = false
            }
            compose.onNodeWithTag("benchmark-run-footer").assertDoesNotExist()
        }
    }

    private fun openScreen() {
        composed = true
        compose.setContent { MaterialTheme { if (show.value) LocalBenchmarkScreen() } }
    }

    private fun awaitReason(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("benchmark-run-reason").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { it.text.contains(text) }
            }
        }
        compose.onNodeWithTag("benchmark-run-reason").assertTextContains(text, substring = true)
    }

    private fun awaitInput(label: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("benchmark-edit-input").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { it.text.contains(label) }
            } && compose.onAllNodesWithTag("benchmark-run-reason").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("benchmark-edit-input").assertTextContains(label, substring = true)
    }

    /** The Play source set uses its local SDK model inventory; initialize that SDK as its provider would. */
    private fun initializeLocalMlKitIfPresent() {
        val sdk = try { Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext") }
            catch (_: ClassNotFoundException) { return }
        // common 18.11.0 exposes initializeIfNeeded(Context); initialize requires a registrar list.
        sdk.getMethod("initializeIfNeeded", Context::class.java).invoke(null, context)
    }
}
