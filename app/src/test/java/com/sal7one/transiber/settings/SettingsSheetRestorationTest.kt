package com.sal7one.transiber.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
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
@OptIn(ExperimentalMaterial3Api::class)
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

    @Test fun returningFromChildIsAlreadyExpandedOnTheFirstFrame() {
        val visible = mutableStateOf(true)
        lateinit var presentation: SheetState
        compose.setContent {
            RetainedSheet(visible.value, loaded = true, onScroll = {}, onPresentation = { presentation = it })
        }
        compose.waitForIdle()
        lateinit var original: SheetState
        compose.runOnIdle {
            original = presentation
            assertEquals(SheetValue.Expanded, original.currentValue)
        }
        val before = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { visible.value = true; Snapshot.sendApplyNotifications() }
            // Only compose/layout the returning page; do not finish an entrance animation.
            compose.mainClock.advanceTimeByFrame()
            val after = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertSame("A child pop reuses the actual expanded presentation object", original, presentation)
                assertEquals(SheetValue.Expanded, presentation.currentValue)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.bottom, after.bottom, 1f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun explicitNewOpeningGetsANewHiddenPresentationWithoutDiscardingOptions() {
        val visible = mutableStateOf(true)
        val session = mutableStateOf(0)
        lateinit var presentation: SheetState
        compose.setContent {
            RetainedSheet(visible.value, loaded = true, onScroll = {},
                presentationSession = session.value, onPresentation = { presentation = it })
        }
        compose.onNodeWithTag("expand-options").performClick()
        lateinit var previous: SheetState
        compose.runOnIdle { previous = presentation; visible.value = false }
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { session.value++; visible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertNotSame("An explicit opening after abandonment starts a new presentation", previous, presentation)
                assertEquals("Keep the legitimate entrance animation", SheetValue.Hidden, presentation.currentValue)
            }
            compose.onNodeWithTag("expanded-options").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, presentation.currentValue) }
    }

    @Test fun nestedFeatureSheetRestoresExpandedBoundsWithoutAnEntrance() {
        val featureVisible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val screens = rememberSaveableStateHolder()
                if (featureVisible.value) screens.SaveableStateProvider("typed-translation") {
                    // Feature screens use the default local SheetState. Its Saver must
                    // retain Expanded when the whole screen is replaced by a child.
                    FeatureOptionsSheet("Typed translation", onDismiss = {},
                        modifier = Modifier.testTag("sheet-viewport")) {
                        repeat(40) { Text("Option $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { featureVisible.value = false }
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { featureVisible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            val after = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.bottom, after.bottom, 1f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun closeFinishesTheNormalHideBeforeRemovingTheSheet() {
        val visible = mutableStateOf(true)
        lateinit var presentation: SheetState
        compose.setContent {
            RetainedSheet(visible.value, loaded = true, onScroll = {},
                onPresentation = { presentation = it }, onDismiss = { visible.value = false })
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, presentation.currentValue) }
        compose.onNodeWithContentDescription("Close Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.runOnIdle { assertEquals("A later explicit open must not inherit Expanded after Close",
            SheetValue.Hidden, presentation.currentValue) }
    }

    @Test fun fastChildPushDuringEntranceReturnsFullyExpandedOnItsFirstFrame() {
        val visible = mutableStateOf(true)
        val session = mutableStateOf(0)
        val retaining = mutableStateOf(false)
        lateinit var presentation: SheetState
        compose.setContent {
            RetainedSheet(visible.value, loaded = true, onScroll = {},
                presentationSession = session.value, retainingForChild = retaining.value,
                onPresentation = { presentation = it })
        }
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { session.value++; visible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Fixture interrupts a genuine entrance", SheetValue.Hidden, presentation.currentValue) }
            compose.runOnUiThread { retaining.value = true; visible.value = false; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
            compose.runOnUiThread { visible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            val returned = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertEquals(SheetValue.Expanded, presentation.currentValue)
                assertEquals(expanded.top, returned.top, 1f)
                assertEquals(expanded.bottom, returned.bottom, 1f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun nestedFeatureFastPushDuringEntranceDoesNotResumeAnEntranceOnBack() {
        val visible = mutableStateOf(true)
        val session = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                val screens = rememberSaveableStateHolder()
                if (visible.value) screens.SaveableStateProvider("feature-${session.value}") {
                    FeatureOptionsSheet("Typed translation", onDismiss = {}, modifier = Modifier.testTag("sheet-viewport")) {
                        repeat(40) { Text("Option $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { session.value++; visible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            val entering = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("Fresh feature opening still animates", entering != expanded)
            compose.runOnUiThread { visible.value = false; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("sheet-viewport").assertDoesNotExist()
            compose.runOnUiThread { visible.value = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            val returned = compose.onNodeWithTag("sheet-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals(expanded.top, returned.top, 1f)
            assertEquals(expanded.bottom, returned.bottom, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Composable
    private fun RetainedSheet(visible: Boolean, loaded: Boolean, onScroll: (ScrollState) -> Unit,
        presentationSession: Int = 0, retainingForChild: Boolean = false,
        onPresentation: (SheetState) -> Unit = {}, onDismiss: () -> Unit = {}) {
        MaterialTheme {
            RetainedSettingsSheet(visible, ready = loaded, presentationSession = presentationSession,
                retainingForChild = retainingForChild) { scroll, presentation ->
                SideEffect { onScroll(scroll); onPresentation(presentation) }
                var expanded by rememberSaveable { mutableStateOf(false) }
                FeatureOptionsSheet("Settings", onDismiss = onDismiss, scroll = scroll,
                    modifier = Modifier.testTag("sheet-viewport"), sheetState = presentation) {
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
