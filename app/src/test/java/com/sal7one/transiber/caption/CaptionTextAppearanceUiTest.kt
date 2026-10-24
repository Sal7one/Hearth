package com.sal7one.transiber.caption

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real subtitle styles and picker gestures; no capture, model or device is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w720dp-h720dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaptionTextAppearanceUiTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val amber = 0xffffc857.toInt()
    private val config = MutableStateFlow(CaptionOverlayConfig(
        mode = CaptionMode.TRANSLATE, display = CaptionDisplay.BOTH, target = TranslationTarget.ARABIC,
        minimalHeader = true, historyLines = 2, backgroundOpacity = 0, textColorArgb = amber, textOpacity = 40,
    ))
    private val state = MutableStateFlow(CaptionEngineController.State(
        status = CaptionEngineController.Status.RUNNING,
        history = listOf(CaptionLine("Earlier source.", translation = "ترجمة سابقة"),
            CaptionLine("Newest source.", translation = "أحدث ترجمة")),
    ))

    @Before fun initialize() = runBlocking { UserPreferences.initialize(compose.activity); Unit }

    private fun showOverlay() {
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CaptionOverlayWindow(config, state, { config.value = it(config.value) },
                    onDrag = { _, _ -> }, onClose = {}, onClear = {}, availableHeightDp = 520f)
            } }
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("The actual Text must expose its layout", action(results))
        }
        return results.single().also {
            assertTrue("Native glyph shaping must have nonzero width", it.getBoundingBox(0).width > 0f)
            assertTrue("Native glyph shaping must have nonzero height", it.getBoundingBox(0).height > 0f)
        }
    }

    private fun assertInk(actual: Color, alpha: Float) {
        val expected = Color(amber)
        assertEquals(expected.red, actual.red, .001f)
        assertEquals(expected.green, actual.green, .001f)
        assertEquals(expected.blue, actual.blue, .001f)
        // sRGB Color stores alpha in eight bits, including the faded × user-opacity product.
        assertEquals(alpha, actual.alpha, 1f / 255f + .0001f)
    }

    @Test fun transparentMinimalOverlayAppliesCustomInkToBothLiveTracksAndFadedChunks() {
        showOverlay()
        val settings = compose.activity.getString(R.string.ui_caption_settings_da0c8)
        compose.onNodeWithContentDescription(settings).assertDoesNotExist()
        fun verifySpans() {
            listOf("caption-live-original", "caption-live-translation").forEach { tag ->
                val tracks = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                tracks.assertCountEquals(2)
                for (index in 0..1) {
                    val measured = layout(tracks[index])
                    val span = measured.layoutInput.text.spanStyles.single().item
                    assertInk(span.color, if (index == 0) .64f * .4f else .4f)
                    assertNotNull("Transparent video captions need a contrast shadow", span.shadow)
                }
            }
        }
        verifySpans()
        compose.runOnIdle { config.value = config.value.copy(backgroundOpacity = 100) }
        listOf("caption-live-original", "caption-live-translation").forEach { tag ->
            for (index in 0..1) {
                val span = layout(compose.onAllNodesWithTag(tag, useUnmergedTree = true)[index])
                    .layoutInput.text.spanStyles.single().item
                assertInk(span.color, if (index == 0) .64f * .4f else .4f)
                assertNull("An opaque bubble does not need a text shadow", span.shadow)
            }
        }
        // The collapsed bubble merges its child captions into one clickable semantics node.
        compose.onNodeWithText("Newest source.", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithContentDescription(settings).assertExists()
        val historyLabel = compose.activity.getString(R.string.ui_read_history_2f89b)
        assertEquals("Caption ink must not recolour or dim controls", paletteFor(config.value.theme).accent,
            layout(compose.onNodeWithText(historyLabel)).layoutInput.style.color)
    }

    @Test fun heldHistoryUsesTheSameCustomInkWithoutApplyingItToReaderControls() {
        config.value = config.value.copy(minimalHeader = false)
        showOverlay()
        compose.onNodeWithText(compose.activity.getString(R.string.ui_read_history_2f89b)).performClick()
        listOf("Earlier source.", "ترجمة سابقة").forEach { text ->
            val measured = layout(compose.onNodeWithText(text))
            assertInk(measured.layoutInput.style.color, .64f * .4f)
            assertNotNull(measured.layoutInput.style.shadow)
        }
        listOf("Newest source.", "أحدث ترجمة").forEach { text ->
            assertInk(layout(compose.onNodeWithText(text)).layoutInput.style.color, .4f)
        }
        val back = compose.activity.getString(R.string.ui_back_to_live_e83c9)
        assertEquals(paletteFor(config.value.theme).accent, layout(compose.onNodeWithText(back)).layoutInput.style.color)
        compose.runOnIdle {
            config.value = config.value.copy(textOpacity = 25)
            state.value = state.value.copy(history = state.value.history + CaptionLine("After held reader."))
        }
        assertInk(layout(compose.onNodeWithText("Newest source.")).layoutInput.style.color, .25f)
        compose.onNodeWithText("After held reader.").assertDoesNotExist()
        compose.onNodeWithText(back).performClick()
        compose.onNodeWithText("After held reader.", substring = true).assertExists()
    }

    @Test fun swatchesAndThemeResetPersistWithoutChangingOpacityAndSurviveReopening() {
        val prefs = mutablePreferencesOf()
        val current = mutableStateOf(config.value)
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (visible.value) CaptionTextAppearanceControls(current.value, { transform ->
                        current.value = transform(current.value).withUiClamp()
                        CaptionConfigStore.writeInto(prefs, current.value)
                    })
                }
            } }
        }
        compose.onNodeWithTag("caption-ink-ffffd54f").performClick().assertIsSelected()
        assertEquals(0xffffd54f.toInt(), CaptionConfigStore.readFrom(prefs).textColorArgb)
        assertEquals(40, CaptionConfigStore.readFrom(prefs).textOpacity)
        compose.onNodeWithTag("caption-ink-theme").performClick().assertIsSelected()
        assertNull(prefs[intPreferencesKey("text_color_argb_v1")])
        assertEquals(40, CaptionConfigStore.readFrom(prefs).textOpacity)

        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("caption-text-appearance").assertDoesNotExist()
        compose.runOnIdle { current.value = CaptionConfigStore.readFrom(prefs); visible.value = true }
        compose.onNodeWithTag("caption-ink-theme").assertIsSelected()
        assertEquals(40f, compose.onNodeWithTag("caption-ink-opacity").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertEquals(0, current.value.backgroundOpacity)
    }

    @Test fun customRgbAndOpacityAccessibilityActionsCommitTheSelectedValues() {
        val prefs = mutablePreferencesOf()
        val current = mutableStateOf(config.value)
        var commits = 0
        var previews = 0
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CaptionTextAppearanceControls(current.value, { transform ->
                        current.value = transform(current.value).withUiClamp()
                        CaptionConfigStore.writeInto(prefs, current.value)
                        commits++
                    }, onPreview = { transform -> current.value = transform(current.value).withUiClamp(); previews++ })
                }
            } }
        }
        compose.onNodeWithTag("caption-ink-custom").performClick()
        listOf("caption-ink-channel-16" to 80f, "caption-ink-channel-8" to 120f,
            "caption-ink-channel-0" to 200f, "caption-ink-opacity" to 55f).forEach { (tag, value) ->
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue("The slider must accept accessibility progress changes", action(value))
            }
        }
        compose.runOnIdle {
            assertEquals("Every completed accessibility adjustment must save once", 4, commits)
            assertEquals(4, previews)
            val saved = CaptionConfigStore.readFrom(prefs)
            assertEquals(0xff5078c8.toInt(), saved.textColorArgb)
            assertEquals(55, saved.textOpacity)
            assertEquals(0, saved.backgroundOpacity)
            assertEquals(CaptionMode.TRANSLATE, saved.mode)
            assertEquals(TranslationTarget.ARABIC, saved.target)
        }
    }

    @Test fun pickerPreviewAndTvUseCustomInkAndIndependentOpacityOnLightAndDarkSurfaces() {
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CaptionTextAppearanceControls(config.value, {})
                    TvOverlayText("TV custom sample", config.value.copy(backgroundOpacity = 100), Modifier.height(100.dp))
                    TvOverlayText("TV theme sample", config.value.copy(textColorArgb = null, theme = CaptionTheme.LIGHT),
                        Modifier.height(100.dp))
                }
            } }
        }
        val previewText = compose.activity.getString(R.string.caption_ink_preview)
        val previews = compose.onAllNodesWithText(previewText)
        previews.assertCountEquals(2)
        for (index in 0..1) assertInk(layout(previews[index]).layoutInput.style.color, .4f)
        val customTv = layout(compose.onNodeWithText("TV custom sample")).layoutInput.style
        assertInk(customTv.color, .4f)
        assertNotNull("TV subtitle shadows remain available over video regardless of bubble opacity", customTv.shadow)
        val themedTv = layout(compose.onNodeWithText("TV theme sample")).layoutInput.style
        assertEquals(Color.White.copy(alpha = .4f), themedTv.color)
        assertNotNull(themedTv.shadow)
    }
}
