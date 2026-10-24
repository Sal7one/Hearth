package com.sal7one.transiber.caption

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.i18n.UiLocaleProvider
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import com.sal7one.transiber.translation.Ct2Package
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
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
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers

/** Real Home/readiness composition; sparse files exercise presence only, never inference or downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w500dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CaptionTranslationReadinessUiTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val context: Context get() = compose.activity
    private val visible = mutableStateOf(true)
    private lateinit var previous: CaptionOverlayConfig
    private lateinit var speechRoot: File
    private lateinit var owner: Owner
    private val backups = mutableListOf<Pair<File, File>>()
    private val modelIds = listOf("ct2-quickmt-en-ar", "ct2-small100-int8")
    private val speechId = "speech-00000000-0000-0000-0000-000000000013"
    private var composed = false

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepare() {
        runBlocking {
            UserPreferences.initialize(context)
            previous = CaptionConfigStore.config(context).first()
        }
        assertFalse("The fixture must never replace a running overlay", CaptionCaptureService.running.value)
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        ShadowVMRuntime.setIs64Bit(true)
        compose.runOnUiThread { owner = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED } }
        modelIds.forEach { id ->
            val root = Ct2Package.directory(context, checkNotNull(Ct2Catalog.find(id)))
            if (root.exists()) {
                val backup = File(root.parentFile, ".ui-backup-${UUID.randomUUID()}")
                Files.move(root.toPath(), backup.toPath()); backups += root to backup
            }
            checkNotNull(Ct2Catalog.find(id)).files.forEach { sparse(File(root, it.name), it.bytes) }
        }
        speechRoot = File(context.filesDir, "speech-models/$speechId")
        check(!speechRoot.exists())
        check(speechRoot.mkdirs())
        // Home discovery reads the package identity only. A real engine open
        // would reject this fixture before inference because it has no weights.
        File(speechRoot, SpeechModelPackage.MANIFEST).writeText(JSONObject()
            .put("schemaVersion", 1).put("profile", SpeechProfile.QWEN3_ASR_0_6B.id).toString())
        select(modelIds.first())
    }

    @After fun restore() {
        if (composed) compose.runOnIdle { visible.value = false }
        if (::previous.isInitialized) runBlocking { CaptionConfigStore.update(context) { previous } }
        if (::speechRoot.isInitialized) speechRoot.deleteRecursively()
        modelIds.forEach { Ct2Package.directory(context, checkNotNull(Ct2Catalog.find(it))).deleteRecursively() }
        backups.forEach { (root, backup) -> Files.move(backup.toPath(), root.toPath()) }
    }

    @Test fun homeRecognizesSelectedQuickMtAndSmall100AndSameIdRepairAfterResume() {
        composed = true
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) {
                        val saved by CaptionConfigStore.config(context).collectAsState(initial = previous)
                        Column(Modifier.fillMaxSize()) {
                            Text(saved.localTranslationModelId, Modifier.testTag("saved-translator"))
                            Box(Modifier.weight(1f)) { CaptionHome({}, {}, {}) }
                        }
                    }
                }
            } }
        }
        awaitStart(modelIds.first())
        val spec = checkNotNull(Ct2Catalog.find(modelIds.first()))
        val component = File(Ct2Package.directory(context, spec), spec.files.first().name)
        // Same saved selection, no model-registry change: lifecycle resume must
        // discover that the underlying install was damaged and then repaired.
        sparse(component, spec.files.first().bytes - 1)
        resume()
        compose.waitUntil(15_000) { nodesContaining("Install ${spec.label}").isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.ui_start_captions_68e1c)).assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.ui_set_up_translation_1d214)).assertCountEquals(2)
        sparse(component, spec.files.first().bytes)
        resume()
        awaitStart(modelIds.first())
        select(modelIds.last())
        awaitStart(modelIds.last())
        assertEquals(modelIds.last(), runBlocking { CaptionConfigStore.config(context).first() }.localTranslationModelId)
    }

    @Test fun unsupportedPairNeverInheritsPreviousReadyStateDuringComposition() {
        val selected = mutableStateOf(local(modelIds.first()))
        val observed = mutableListOf<Pair<String, CaptionTranslationReadiness>>()
        composed = true
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    val config = selected.value
                    val readiness = rememberCaptionTranslationReadiness(config, CloudConfigStore.SttMode.BATCH)
                    SideEffect { observed += config.streamLanguage to readiness }
                    Column {
                        Text(readiness.detail, Modifier.testTag("readiness-detail"))
                        Button({}, enabled = readiness.ready, modifier = Modifier.testTag("ready-start")) { Text("Start") }
                    }
                }
            } }
        }
        compose.waitUntil(15_000) { observed.lastOrNull()?.second?.ready == true }
        compose.onNodeWithTag("ready-start").assertIsEnabled()
        compose.runOnIdle { selected.value = selected.value.copy(streamLanguage = "ru") }
        compose.waitUntil(15_000) { nodesContaining("does not support ru → ar").isNotEmpty() }
        compose.onNodeWithTag("ready-start").assertIsNotEnabled()
        compose.runOnIdle {
            val changed = observed.filter { it.first == "ru" }
            assertTrue(changed.isNotEmpty())
            assertTrue("A changed pair must begin pending rather than expose the old model's ready result", changed.first().second.checking)
            assertTrue("No composition of the unsupported pair may retain previous readiness", changed.none { it.second.ready })
            assertFalse(changed.last().second.detail.contains("Choose a local translator"))
        }
    }

    private fun local(id: String) = CaptionOverlayConfig(engine = CaptionEngineChoice.QWEN, modelId = speechId,
        mode = CaptionMode.TRANSLATE, localTranslationEnabled = true, textTranslationProviderId = "local",
        localTranslationModelId = id, streamLanguage = "en", target = TranslationTarget.ARABIC)
    private fun select(id: String) = runBlocking { CaptionConfigStore.update(context) { local(id) } }
    private fun sparse(file: File, size: Long) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }
    private fun resume() = compose.runOnUiThread {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    private fun nodesContaining(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
    private fun awaitStart(id: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("saved-translator").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == id }
            } && nodesContaining(context.getString(R.string.ui_start_captions_68e1c)).isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.ui_start_captions_68e1c)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.ui_set_up_translation_1d214)).assertDoesNotExist()
    }
}
