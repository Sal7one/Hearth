package com.sal7one.transiber.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real sheet composition/layout, rather than an assertion about navigation booleans alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsSheetRestorationTest {
    @get:Rule val compose = AndroidComposeTestRule(RobolectricActivityRule()) { it.activity }

    @Test fun returningFromChildKeepsScrollAndExpandedOptions() {
        val visible = mutableStateOf(true)
        lateinit var scroll: ScrollState
        compose.setContent {
            RetainedSheet(visible.value, loaded = true, onScroll = { scroll = it })
        }
        compose.onNodeWithTag("expand-options").performClick()
        compose.onNodeWithTag("expanded-options").assertExists()
        compose.runOnIdle {
            assertTrue("Fixture must really overflow the sheet", scroll.maxValue > 500)
            runBlocking { scroll.scrollTo(500) }
        }
        compose.runOnIdle { assertEquals(500, scroll.value); visible.value = false }
        compose.onNodeWithTag("expanded-options").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("expanded-options").assertExists()
        compose.runOnIdle { assertEquals("Back must restore the same option position", 500, scroll.value) }
    }

    @Test fun restoredSheetWaitsForPipelineDataWithoutClampingSavedScroll() {
        val visible = mutableStateOf(true)
        val loaded = mutableStateOf(true)
        lateinit var scroll: ScrollState
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RetainedSheet(visible.value, loaded.value, onScroll = { scroll = it })
        }
        compose.onNodeWithTag("expand-options").performClick()
        compose.runOnIdle {
            assertTrue(scroll.maxValue > 600)
            runBlocking { scroll.scrollTo(600) }
            // Navigate away; the child can outlive the original data collector.
            visible.value = false
        }
        compose.onNodeWithTag("expanded-options").assertDoesNotExist()
        compose.runOnIdle { loaded.value = false; visible.value = true }
        // Process/configuration restoration can happen while DataStore has not emitted yet.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sheet-row-0").assertDoesNotExist()
        compose.runOnIdle { loaded.value = true }
        compose.onNodeWithTag("expanded-options").assertExists()
        compose.runOnIdle { assertEquals("Loaded content must reuse the restored position", 600, scroll.value) }
    }

    @Composable
    private fun RetainedSheet(visible: Boolean, loaded: Boolean, onScroll: (ScrollState) -> Unit) {
        MaterialTheme {
            RetainedSettingsSheet(visible, ready = loaded) { scroll ->
                SideEffect { onScroll(scroll) }
                var expanded by rememberSaveable { mutableStateOf(false) }
                FeatureOptionsSheet("Settings", onDismiss = {}, scroll = scroll) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("expand-options")) {
                        Text("Translation options")
                    }
                    if (expanded) Text("Saved inline options", Modifier.testTag("expanded-options"))
                    repeat(40) { row ->
                        Text("Option $row", Modifier.height(48.dp).testTag("sheet-row-$row"))
                    }
                }
            }
        }
    }

    /** ActivityScenario requires a manifest entry; a direct host keeps that test detail out of APKs. */
    class RobolectricActivityRule : TestRule {
        private lateinit var controller: ActivityController<ComponentActivity>
        val activity: ComponentActivity get() = controller.get()

        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
                try { base.evaluate() } finally { controller.pause().stop().destroy() }
            }
        }
    }
}
