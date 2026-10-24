package com.sal7one.transiber.reading

import android.app.Application
import android.view.View
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.shadows.ShadowDialog

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

    @Test fun visualColourPickerIsCollapsedAndPersistsBothColoursWithoutResettingPageMatching() {
        val activity = compose.activity
        val preferences = ReadingOverlayPreferences(activity)
        compose.runOnIdle { preferences.update { it.copy(replaceText = true, mode = "page", boxHeightPercent = 165) } }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithTag("reading-colour-picker-foreground").assertDoesNotExist()
        compose.onNodeWithTag("reading-colour-picker-background").assertDoesNotExist()
        val swatch = activity.getString(R.string.reading_style_colour_choice,
            activity.getString(R.string.reading_style_foreground), "#55E0CB")
        compose.onNodeWithContentDescription(swatch).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(preferences.read().replaceText)
            assertEquals(0xff55e0cb.toInt(), preferences.read().boxTextArgb)
            assertEquals("page", preferences.read().mode)
        }
        compose.onNodeWithTag("reading-colour-foreground").performScrollTo().performClick()
        compose.onNodeWithTag("reading-colour-picker-foreground").performScrollTo().assertExists()
        lateinit var hex: EditText
        compose.runOnIdle {
            hex = optionsView("hearth_colour_hex")
            hex.setText("#37A9C5")
            preferences.update { it.copy(boxWidthPercent = 92, passThrough = false) }
        }
        compose.runOnIdle {
            assertEquals("An unrelated preference update must retain an unfinished hex edit", "#37A9C5", hex.text.toString())
            assertTrue(optionsView<EditText>("hearth_colour_hex") === hex)
            optionsView<View>("hearth_colour_apply").performClick()
        }
        compose.runOnIdle {
            val restored = preferences.read()
            assertEquals(0xff37a9c5.toInt(), restored.boxTextArgb)
            assertTrue(restored.replaceText); assertEquals("page", restored.mode)
            assertEquals(92, restored.boxWidthPercent); assertEquals(165, restored.boxHeightPercent)
            assertFalse(restored.passThrough)
        }
        compose.onNodeWithTag("reading-colour-background").performScrollTo().performClick()
        compose.onNodeWithTag("reading-colour-picker-foreground").assertDoesNotExist()
        compose.onNodeWithTag("reading-colour-picker-background").performScrollTo().assertExists()
        compose.runOnIdle {
            optionsView<EditText>("hearth_colour_hex").setText("#191A2B")
            optionsView<View>("hearth_colour_apply").performClick()
        }
        compose.runOnIdle {
            val restored = ReadingOverlayPreferences(activity).read()
            assertEquals(0xff191a2b.toInt(), restored.boxBackgroundArgb)
            assertEquals(0xff37a9c5.toInt(), restored.boxTextArgb)
            assertTrue(restored.replaceText)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        compose.onNodeWithTag("reading-colour-background").performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(0xff191a2b.toInt(), preferences.read().boxBackgroundArgb)
            assertEquals(0xff37a9c5.toInt(), preferences.read().boxTextArgb)
        }
    }

    @Test fun solidReplacementShowsEffectiveTouchThroughAndRestoresSavedOpacityAndTouchChoices() {
        val activity = compose.activity
        val preferences = ReadingOverlayPreferences(activity)
        compose.runOnIdle { preferences.update { it.copy(passThrough = true, boxOpacity = 35,
            boxWidthPercent = 82, boxHeightPercent = 165, replaceText = false, showOriginal = false, translationVisible = true) } }
        compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_options)).performClick()
        val passThrough = compose.onNodeWithContentDescription(activity.getString(R.string.reading_passthrough))
        val opacity = compose.onNodeWithContentDescription(activity.getString(R.string.reading_style_opacity))
        val replace = compose.onNodeWithContentDescription(activity.getString(R.string.reading_style_replace))
        val original = compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_show_original))
        val translations = compose.onNodeWithContentDescription(activity.getString(R.string.reading_setup_show_translation))
        fun assertOpacity(expected: Float) {
            assertEquals(expected, opacity.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        }
        passThrough.assertIsOn().assertIsEnabled()
        opacity.assertIsEnabled(); assertOpacity(35f)
        replace.performScrollTo().performClick()
        passThrough.assertIsOff().assertIsNotEnabled()
        opacity.assertIsNotEnabled(); assertOpacity(100f)
        compose.runOnIdle {
            val saved = preferences.read()
            assertTrue("Opaque replacement changes effective touch behavior, not the saved preference", saved.passThrough)
            assertEquals("Regular-box opacity must remain available after replacement is turned off", 35, saved.boxOpacity)
            assertEquals(82, saved.boxWidthPercent); assertEquals(165, saved.boxHeightPercent)
        }
        original.performScrollTo().performClick()
        passThrough.assertIsOn().assertIsEnabled()
        original.performClick()
        passThrough.assertIsOff().assertIsNotEnabled()
        translations.performScrollTo().performClick()
        passThrough.assertIsOn().assertIsEnabled()
        translations.performClick()
        passThrough.assertIsOff().assertIsNotEnabled()
        replace.performScrollTo().performClick()
        passThrough.assertIsOn().assertIsEnabled()
        opacity.assertIsEnabled(); assertOpacity(35f)
        compose.runOnIdle {
            val saved = preferences.read()
            assertFalse(saved.replaceText); assertTrue(saved.passThrough)
            assertFalse(saved.showOriginal); assertTrue(saved.translationVisible)
            assertEquals(35, saved.boxOpacity)
            assertEquals(82, saved.boxWidthPercent); assertEquals(165, saved.boxHeightPercent)
        }
    }

    /** Material's options sheet owns a Dialog window, separate from the Activity decor. */
    private fun <T : View> optionsView(tag: String): T {
        val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "Options must have a real sheet window" }
        assertTrue("The options sheet must remain open while choosing a colour", dialog.isShowing)
        return checkNotNull(dialog.window?.decorView?.findViewWithTag<T>(tag)) {
            "The actual options sheet must contain $tag"
        }
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
