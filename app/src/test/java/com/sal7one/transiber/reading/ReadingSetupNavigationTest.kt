package com.sal7one.transiber.reading

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sal7one.transiber.R
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.shortcuts.OverlaySetupVisibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise the actual setup Activity without requesting projection or loading a speech/OCR engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ReadingSetupNavigationTest {
    private val activityRule = ReadingActivityRule()
    @get:Rule val compose = AndroidComposeTestRule(activityRule) { it.activity }

    @Test fun activityVisibilityReleasesAndReacquiresOverlaySuppressionWithoutLeaks() {
        compose.runOnIdle {
            assertTrue("A visible setup must hide service overlays", OverlaySetupVisibility.active.value)
            repeat(2) {
                activityRule.leaveSetup()
                assertFalse("Leaving setup must release its overlay visibility lease", OverlaySetupVisibility.active.value)
                activityRule.returnToSetup()
                assertTrue("Returning to setup must acquire a new lease", OverlaySetupVisibility.active.value)
            }
        }
    }

    @Test fun backClosesOptionsBeforeLeavingSetup() {
        val activity = compose.activity
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithText(activity.getString(R.string.reading_setup_display)).assertIsDisplayed()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(activity.getString(R.string.reading_setup_display)).assertDoesNotExist()
        compose.runOnIdle { assertFalse("Back must dismiss the options, not the setup Activity", activity.isFinishing) }
        compose.onNodeWithText(activity.getString(R.string.reading_setup_title)).assertExists()
    }

    @Test fun overlayPreferenceChangesAppearWithoutRestartingOrResettingTheSavedMode() {
        val activity = compose.activity
        val preferences = ReadingOverlayPreferences(activity)
        compose.runOnIdle { preferences.update { it.copy(mode = "page", textSizeSp = 30f) } }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithText(activity.getString(R.string.reading_setup_text_size, 30)).assertExists()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(activity.getString(R.string.reading_setup_display)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("page", preferences.read().mode) }
    }

    @Test fun setupAppearanceUsesTheSameSavedPaletteAsTheLiveOverlayAndKeepsOtherChoices() {
        val activity = compose.activity
        val preferences = ReadingOverlayPreferences(activity)
        compose.runOnIdle { preferences.update { it.copy(mode = "page", passThrough = false,
            boxWidthPercent = 85, boxHeightPercent = 165, textSizeSp = 27f) } }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_style_replace)).performScrollTo().performClick()
        compose.onNodeWithText(activity.getString(R.string.reading_style_warm)).performScrollTo().performClick()
        compose.runOnIdle {
            val restored = ReadingOverlayPreferences(activity).read()
            assertTrue(restored.replaceText); assertTrue(ReadingBoxPreset.WARM.matches(restored))
            assertEquals(85, restored.boxWidthPercent); assertEquals(165, restored.boxHeightPercent)
            assertEquals("page", restored.mode); assertFalse(restored.passThrough); assertEquals(27f, restored.textSizeSp)
        }
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithText(activity.getString(R.string.reading_style_warm)).assertExists()
    }

    @Test fun setupRgbIsCollapsedAndAColourChoiceKeepsPageMatchingEnabled() {
        val activity = compose.activity
        val preferences = ReadingOverlayPreferences(activity)
        compose.runOnIdle { preferences.update { it.copy(replaceText = true, mode = "page") } }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_style_red)).assertDoesNotExist()
        val swatch = activity.getString(R.string.reading_style_colour_choice,
            activity.getString(R.string.reading_style_foreground), "#55E0CB")
        compose.onNodeWithContentDescription(swatch).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(preferences.read().replaceText)
            assertEquals(0xff55e0cb.toInt(), preferences.read().boxTextArgb)
            assertEquals("page", preferences.read().mode)
        }
        compose.onNodeWithText(activity.getString(R.string.reading_style_rgb)).performScrollTo().performClick()
        compose.onAllNodesWithContentDescription(activity.getString(R.string.reading_style_blue)).assertCountEquals(2)
    }

    class ReadingActivityRule : TestRule {
        private lateinit var controller: ActivityController<ReadingStartActivity>
        val activity: ReadingStartActivity get() = controller.get()
        fun leaveSetup() { controller.pause().stop() }
        fun returnToSetup() { controller.start().resume().visible() }
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = RuntimeEnvironment.getApplication()
                runBlocking { UserPreferences.initialize(context) }
                check(UserPreferences.store(context, "reading-overlay").edit().clear().commit())
                controller = Robolectric.buildActivity(ReadingStartActivity::class.java)
                controller.get().setTheme(R.style.AppTheme)
                controller.setup().visible()
                try { base.evaluate() } finally {
                    controller.pause().stop().destroy()
                    assertFalse("Destroyed reading setup must not leave overlays suppressed", OverlaySetupVisibility.active.value)
                }
            }
        }
    }
}
