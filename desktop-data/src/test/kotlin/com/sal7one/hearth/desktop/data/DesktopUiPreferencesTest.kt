package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopUiPreferencesTest {
    @Test fun speechWindowUsesTheRuntimeBoundsAndSurvivesPortableSetup() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-speech-window-")
        val store = DesktopSettingsStore(directory)
        try {
            for (window in listOf(1_000, 2_000, 4_000, 12_000, 30_000)) {
                val config = PipelineConfig(speech = EngineSelection("whisper-local", options = mapOf("windowMs" to "$window")))
                store.save(config)
                assertEquals("$window", store.load().speech?.options?.get("windowMs"))
                assertEquals("$window", SetupCodec.decode(SetupCodec.encode(config), portable = true).speech?.options?.get("windowMs"))
            }
            for (window in listOf("999", "30001", "invalid", "2147483648")) {
                assertFailsWith<IllegalArgumentException> {
                    SetupCodec.encode(PipelineConfig(speech = EngineSelection("whisper-local", options = mapOf("windowMs" to window))))
                }
            }
        } finally { store.closeAndJoin(); directory.toFile().deleteRecursively() }
    }

    @Test fun independentUiPatchesSurviveNavigationAndReopening() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-ui-patches-")
        try {
            DesktopSettingsStore(directory).let { store ->
                try {
                    store.saveUi(mapOf("overlay-pending" to "false", "overlay-visible" to "true"))
                    store.saveUi(mapOf("palette" to "INK", "model-stage" to "STT"))
                    store.saveUi(mapOf("screen" to "captions"))
                    // An explicit empty selection resets that preference, preserving all others.
                    store.saveUi(mapOf("model-stage" to ""))
                } finally { store.closeAndJoin() }
            }
            DesktopSettingsStore(directory).let { store ->
                try {
                    store.load()
                    assertEquals(mapOf("overlay-pending" to "false", "overlay-visible" to "true",
                        "palette" to "INK", "model-stage" to "", "screen" to "captions"), store.ui.value)
                } finally { store.closeAndJoin() }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun concurrentPatchesMergeAndCombinedLimitRemainsAtomic() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-ui-concurrent-")
        val store = DesktopSettingsStore(directory)
        try {
            coroutineScope {
                repeat(64) { index -> launch(Dispatchers.Default) { store.saveUi(mapOf("setting-$index" to "$index")) } }
            }
            assertFailsWith<IllegalArgumentException> { store.saveUi(mapOf("overflow" to "value")) }
            store.load()
            assertEquals((0 until 64).associate { "setting-$it" to "$it" }, store.ui.value)
        } finally { store.closeAndJoin(); directory.toFile().deleteRecursively() }
    }
}
