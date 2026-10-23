package com.sal7one.transiber.benchmark

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual Compose semantics/actions, including the previously invisible disabled reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BenchmarkRunFooterTest {
    @get:Rule val compose = AndroidComposeTestRule(ActivityRule()) { it.activity }

    @Test fun readySelectionRunsAndInputCanBeEditedWithoutScrolling() {
        var runs = 0
        var edits = 0
        compose.setContent {
            MaterialTheme {
                BenchmarkRunFooter(null, 20, 3, "7 Arabic samples", true, false, false, "", null,
                    onRun = { runs++ }, onStop = {}, onFix = {}, onEditInput = { edits++ })
            }
        }
        compose.onNodeWithTag("benchmark-run-footer").assertIsDisplayed()
        compose.onNodeWithTag("benchmark-compare").assertIsEnabled().performClick()
        compose.onNodeWithTag("benchmark-edit-input").assertTextContains("7 Arabic samples", substring = true).performClick()
        compose.onNodeWithTag("benchmark-run-reason").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, runs); assertEquals(1, edits) }
    }

    @Test fun missingSaudiInputKeepsRunDisabledAndOffersTheCorrectFix() {
        var fixes: BenchmarkReadinessFix? = null
        var runs = 0
        var bundled = 0
        compose.setContent {
            MaterialTheme {
                BenchmarkRunFooter(BenchmarkReadinessIssue.EMPTY_SAUDI_SET, 20, 3, "Saudi recordings", false,
                    false, false, "", null, onRun = { runs++ }, onStop = {}, onFix = { fixes = it }, onEditInput = {},
                    onUseBundledSpeech = { bundled++ })
            }
        }
        compose.onNodeWithTag("benchmark-compare").assertIsNotEnabled()
        compose.onNodeWithTag("benchmark-run-reason").assertTextContains("No Saudi recordings", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("benchmark-fix").assertIsDisplayed().performClick()
        compose.onNodeWithTag("benchmark-use-bundled").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(BenchmarkReadinessFix.INPUT, fixes); assertEquals(0, runs); assertEquals(1, bundled) }
    }

    @Test fun selectedModelsStayUsableAfterInitializationFinishes() {
        val issue = mutableStateOf<BenchmarkReadinessIssue?>(BenchmarkReadinessIssue.INITIALIZING)
        compose.setContent {
            MaterialTheme {
                BenchmarkRunFooter(issue.value, 20, 3, "Bundled samples", false, false, false, "", null,
                    onRun = {}, onStop = {}, onFix = {}, onEditInput = {})
            }
        }
        compose.onNodeWithTag("benchmark-compare").assertIsNotEnabled()
        compose.onNodeWithTag("benchmark-run-reason").assertTextContains("Checking installed models", substring = true)
        compose.onNodeWithTag("benchmark-fix").assertDoesNotExist()
        compose.runOnIdle { issue.value = null }
        compose.onNodeWithTag("benchmark-compare").assertIsEnabled()
        compose.onNodeWithTag("benchmark-run-reason").assertDoesNotExist()
    }

    @Test fun gateOwnerIsNamedAndItsFixStopsTheOwningTask() {
        var fix: BenchmarkReadinessFix? = null
        compose.setContent {
            MaterialTheme {
                BenchmarkRunFooter(BenchmarkReadinessIssue.ENGINE_IN_USE, 20, 3, "Bundled samples", false,
                    false, false, "", "Captions", onRun = {}, onStop = {}, onFix = { fix = it }, onEditInput = {})
            }
        }
        compose.onNodeWithTag("benchmark-run-reason").assertTextContains("Captions", substring = true)
        compose.onNodeWithTag("benchmark-compare").assertIsNotEnabled()
        compose.onNodeWithTag("benchmark-fix").performClick()
        compose.runOnIdle { assertEquals(BenchmarkReadinessFix.STOP_ACTIVITY, fix) }
    }

    @Test fun aRunningComparisonAlwaysOffersStopAndShowsCleanupState() {
        var stops = 0
        val stopping = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                BenchmarkRunFooter(null, 20, 3, "Bundled samples", false, true, stopping.value,
                    "2/20 · loading next model", "Benchmark", onRun = {}, onStop = { stops++ }, onFix = {}, onEditInput = {})
            }
        }
        compose.onNodeWithTag("benchmark-compare").assertDoesNotExist()
        compose.onNodeWithTag("benchmark-run-status").assertTextContains("2/20", substring = true)
        compose.onNodeWithTag("benchmark-stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops); stopping.value = true }
        compose.onNodeWithTag("benchmark-stop").assertIsNotEnabled()
    }

    class ActivityRule : TestRule {
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
