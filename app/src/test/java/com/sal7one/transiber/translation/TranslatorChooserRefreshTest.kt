package com.sal7one.transiber.translation

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelDigestAlgorithm
import com.sal7one.common_jni.model.ModelDigestKind
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.R
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsLocation
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.util.ReflectionHelpers

/** Real chooser callbacks and resume/installation signals, without loading weights or downloading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w720dp-h1200dp")
@LooperMode(LooperMode.Mode.PAUSED)
class TranslatorChooserRefreshTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val context: Context get() = compose.activity.applicationContext
    private lateinit var previousRegistry: ModelRegistry
    private lateinit var registry: ModelRegistry
    private val fixtureFiles = mutableListOf<File>()
    private val owner = Owner()
    private var selected = 0
    private var openedModels = 0

    @Before fun prepare() {
        runBlocking { UserPreferences.initialize(context) }
        previousRegistry = ModelRegistry.getInstance(context)
        ReflectionHelpers.setStaticField(ModelRegistry::class.java, "instance", null)
        registry = ModelRegistry.getInstance(context)
        registrations().value = emptyList()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @After fun restore() {
        fixtureFiles.forEach { it.deleteRecursively() }
        ReflectionHelpers.setStaticField(ModelRegistry::class.java, "instance", previousRegistry)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
    }

    private fun show(id: String) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TranslatorChooser("local", id, "Fixture scope", source = "en", target = "ar",
                            location = SettingsLocation.LOCAL,
                            onSelect = { provider, chosen -> assertEquals("local", provider); assertEquals(id, chosen); selected++ },
                            onModels = { openedModels++ })
                    }
                }
            }
        }
        awaitInventory()
    }

    private fun awaitInventory() {
        val checking = context.getString(R.string.ui_checking_820d6)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(checking).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private fun click(id: String) {
        compose.onNodeWithText(TranslationOptions.label(id)).performScrollTo().performClick()
    }

    @Test fun sameGgufSelectionRecoversAfterImportSignalAndRechecksRemovedFileOnResume() {
        val spec = TranslationCatalog.models.minBy { it.bytes }
        val file = File(context.filesDir, "translation-models/${spec.id}.gguf")
        check(!file.exists())
        fixtureFiles += file
        show(spec.id)
        click(spec.id)
        compose.runOnIdle { assertEquals(1, openedModels); assertEquals(0, selected) }

        // File sizes stand in for an already verified import; no fake weights are opened for inference.
        sparse(file, spec.bytes)
        runBlocking { ModelSourceBindings.recheck(context) }
        awaitInventory()
        click(spec.id)
        compose.runOnIdle { assertEquals(1, selected); assertEquals(1, openedModels) }

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        check(file.delete())
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitInventory()
        click(spec.id)
        compose.runOnIdle { assertEquals(1, selected); assertEquals(2, openedModels) }
    }

    @Test fun marianRegistrationRefreshRequiresCompleteComponentsAndDetectsTruncationOnResume() {
        val pair = checkNotNull(MarianPackage.find("marian-en-ar"))
        val root = File(context.filesDir, "chooser-marian-fixture")
        fixtureFiles += root
        root.mkdirs()
        show(pair.id)
        click(pair.id)
        compose.runOnIdle { assertEquals(0, selected); assertEquals(1, openedModels) }

        pair.parts.forEach { sparse(File(root, it.fileName), it.bytes) }
        compose.runOnIdle { registrations().value = listOf(record(pair, root)) }
        awaitInventory()
        click(pair.id)
        compose.runOnIdle { assertEquals(1, selected) }

        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        sparse(File(root, pair.parts.last().fileName), pair.parts.last().bytes - 1)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitInventory()
        click(pair.id)
        compose.runOnIdle { assertEquals(1, selected); assertEquals(2, openedModels) }
    }

    @Test fun inventoryIncludesCt2AndRequiresBothMarianPivotPackages() {
        val route = MarianCascade.routes.first()
        val roots = listOf(route.first, route.second).map { pair ->
            File(context.filesDir, "chooser-${pair.id}").also { root ->
                fixtureFiles += root
                pair.parts.forEach { sparse(File(root, it.fileName), it.bytes) }
            }
        }
        val records = listOf(record(route.first, roots[0]), record(route.second, roots[1]))
        assertFalse(route.id in installedTranslatorChoices(context, records.take(1)))
        assertTrue(route.id in installedTranslatorChoices(context, records))
        check(File(roots[1], route.second.parts.first().fileName).delete())
        assertFalse(route.id in installedTranslatorChoices(context, records))
        val spec = checkNotNull(Ct2Catalog.find("ct2-small100-int8"))
        val ct2 = Ct2Package.directory(context, spec)
        check(!ct2.exists())
        fixtureFiles += ct2
        spec.files.forEach { sparse(File(ct2, it.name), it.bytes) }
        assertTrue(spec.id in installedTranslatorChoices(context, emptyList()))
        check(File(ct2, spec.files.first().name).delete())
        assertFalse(spec.id in installedTranslatorChoices(context, emptyList()))
    }

    private fun record(pair: MarianPackage.Pair, root: File) = ModelRegistry.RegisteredModel(
        "fixture-${pair.id}", "Prior verified install fixture", ModelEngineType.TRANSLATE,
        ModelRegistry.ModelSource.INTERNAL, root.path, pair.downloadBytes, true, true, 0,
        ModelDigest(ModelDigestAlgorithm.SHA256, ModelDigestKind.TREE, pair.treeSha256, ModelDigestTrust.PINNED))

    private fun registrations(): MutableStateFlow<List<ModelRegistry.RegisteredModel>> =
        ReflectionHelpers.getField(registry, "_registeredModels")

    private fun sparse(file: File, bytes: Long) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
