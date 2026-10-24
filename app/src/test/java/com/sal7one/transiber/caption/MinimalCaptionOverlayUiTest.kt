package com.sal7one.transiber.caption

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.UiLocaleProvider
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Exercise the real Compose overlay on a host; no capture, device or model is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w720dp-h320dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class MinimalCaptionOverlayUiTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val config = MutableStateFlow(CaptionOverlayConfig(minimalHeader = true,
        mode = CaptionMode.TRANSLATE, display = CaptionDisplay.TRANSLATED, target = TranslationTarget.ARABIC))
    private val state = MutableStateFlow(CaptionEngineController.State(status = CaptionEngineController.Status.RUNNING,
        history = listOf(CaptionLine("Original speech", translation = "النص المترجم")),
        localSpeechMetrics = "Hidden speech timing", localTranslationMetrics = "Hidden translation timing"))

    @Before fun initializePreferences() = runBlocking { UserPreferences.initialize(compose.activity) }

    private fun showOverlay() {
        compose.setContent {
            val current by config.collectAsState()
            MaterialTheme {
                UiLocaleProvider {
                    CaptionOverlayWindow(config, state, { config.value = it(config.value) },
                        onDrag = { _, _ -> }, onClose = {}, onClear = {},
                        availableHeightDp = overlayHeightPx(320, 1f, current).toFloat())
                }
            }
        }
    }

    @Test fun compactCaptionsHideChromeAndTapRevealsReachableControls() {
        showOverlay()
        val settings = compose.activity.getString(R.string.ui_caption_settings_da0c8)
        compose.onNodeWithContentDescription(settings).assertDoesNotExist()
        compose.onNodeWithText("Original speech").assertDoesNotExist()
        compose.onNodeWithText("Hidden speech timing").assertDoesNotExist()
        compose.onNodeWithText("Hidden translation timing").assertDoesNotExist()
        compose.onNodeWithText("النص المترجم").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(settings).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(config.value.minimalHeaderExpanded)
            assertEquals(64, config.value.minimalHeightDp)
            assertEquals(160, overlayHeightPx(320, 1f, config.value))
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.caption_hide_controls)).performClick()
        compose.onNodeWithContentDescription(settings).assertDoesNotExist()
        compose.onNodeWithText("النص المترجم").assertIsDisplayed()
        compose.runOnIdle { assertEquals(64, overlayHeightPx(320, 1f, config.value)) }
    }

    @Test fun settingsRemainOpenAndClosingThemRestoresTheCompactViewport() {
        showOverlay()
        compose.onNodeWithText("النص المترجم").performClick()
        val settings = compose.activity.getString(R.string.ui_caption_settings_da0c8)
        compose.onNodeWithContentDescription(settings).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.caption_minimal_header)).assertExists()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12)) }
        compose.runOnIdle {
            assertTrue(config.value.showSettings)
            assertEquals(64, config.value.minimalHeightDp)
        }
        compose.onNodeWithContentDescription(settings).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.caption_hide_controls)).performClick()
        compose.onNodeWithContentDescription(settings).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(config.value.showSettings)
            assertEquals(64, overlayHeightPx(320, 1f, config.value))
        }
    }

    @Test fun tapThroughAlsoHidesTheRecoveryBannerAndNeverRevealsInlineControls() {
        config.value = config.value.copy(tapThrough = true)
        showOverlay()
        compose.onNodeWithText("النص المترجم").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.ui_tap_lock_to_restore_controls_drag_lock_to_move_20d73)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.ui_caption_settings_da0c8)).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(config.value.minimalHeaderExpanded)
            assertEquals(64, overlayHeightPx(320, 1f, config.value))
        }
    }

    @Test fun idleControlsCollapseWithoutChangingTheSelectedCaptionText() {
        showOverlay()
        compose.onNodeWithText("النص المترجم").performClick()
        val settings = compose.activity.getString(R.string.ui_caption_settings_da0c8)
        compose.onNodeWithContentDescription(settings).assertIsDisplayed()
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6)) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithContentDescription(settings).assertDoesNotExist()
        compose.onNodeWithText("النص المترجم").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(config.value.minimalHeaderExpanded)
            assertEquals(CaptionDisplay.TRANSLATED, config.value.display)
            assertEquals(64, config.value.minimalHeightDp)
        }
    }
}
