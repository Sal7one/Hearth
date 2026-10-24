package com.sal7one.transiber.caption

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
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

/** Real subtitle layout and reading controls; no capture service, model or native inference is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="en-rUS-w720dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CaptionOverlayTextLayoutTest {
    @get:Rule val compose=AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private val config=MutableStateFlow(CaptionOverlayConfig(mode=CaptionMode.CAPTIONS,historyLines=4,
        minimalHeader=true,tapThrough=true))
    private val state=MutableStateFlow(CaptionEngineController.State(status=CaptionEngineController.Status.RUNNING))

    @Before fun preferences() = runBlocking { UserPreferences.initialize(compose.activity);Unit }

    private fun show() {
        compose.setContent {
            MaterialTheme { UiLocaleProvider {
                CaptionOverlayWindow(config,state,{ config.value=it(config.value) },
                    onDrag={ _,_-> },onClose={},onClear={},availableHeightDp=400f)
            } }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("Subtitle must expose its actual text layout",action(results))
        }
        return results.single().also { measured ->
            // Legacy Robolectric graphics supplies zero-width glyphs: geometry
            // assertions would otherwise pass without testing a shaped paragraph.
            assertTrue("Native subtitle glyphs must have nonzero width: ${measured.size}, lines=${measured.lineCount}",
                measured.getLineRight(0)-measured.getLineLeft(0)>1f)
            assertTrue("Native subtitle glyphs must have nonzero height",measured.getBoundingBox(0).height>1f)
        }
    }

    @Test fun shortUtterancesContinueOnOneFullWidthLiveLineInsteadOfCreatingVerticalRows() {
        state.value=state.value.copy(history=listOf(CaptionLine("One."),CaptionLine("Two.")),partial="Three.")
        show()
        val actual=layout("caption-live-original")
        assertEquals("One. Two. Three.",actual.layoutInput.text.text)
        assertEquals(1,actual.lineCount)
        assertEquals(0,actual.getLineForOffset(actual.layoutInput.text.text.indexOf("Two.")))
        assertEquals(0,actual.getLineForOffset(actual.layoutInput.text.text.indexOf("Three.")))
        val paragraph=compose.onNodeWithTag("caption-live-original").fetchSemanticsNode().boundsInRoot
        val viewport=compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("A short subtitle must still occupy the overlay width",paragraph.width>viewport.width*.85f)
        assertTrue("English must start at the left side of its full-width paragraph",actual.getLineLeft(0)<2f)
    }

    @Test fun arabicSourceAndTranslationUseTheRightEdgeEvenWhenTheApplicationIsEnglish() {
        state.value=state.value.copy(history=listOf(CaptionLine("مرحبا بالعالم")))
        config.value=config.value.copy(target=TranslationTarget.ENGLISH)
        show()
        val source=layout("caption-live-original")
        assertTrue("Arabic source must not inherit an English target's direction",source.getLineLeft(0)>source.size.width*.5f)
        assertEquals(source.size.width.toFloat(),source.getLineRight(0),2f)

        compose.runOnIdle {
            config.value=config.value.copy(mode=CaptionMode.TRANSLATE,display=CaptionDisplay.BOTH,target=TranslationTarget.ARABIC)
            state.value=state.value.copy(history=listOf(CaptionLine("Hello world",translation="مرحبا بالعالم")))
        }
        val original=layout("caption-live-original")
        val translation=layout("caption-live-translation")
        assertTrue("Arabic target must not right-align English source",original.getLineLeft(0)<2f)
        assertTrue("Arabic translation must use available width",translation.getLineLeft(0)>translation.size.width*.5f)
        assertEquals(translation.size.width.toFloat(),translation.getLineRight(0),2f)
    }

    @Test fun longLiveParagraphWrapsWithinTheOverlayAndKeepsTheSelectedSegmentBound() {
        val long="The final subtitle wraps naturally across the overlay width. ".repeat(30).trim()
        config.value=config.value.copy(historyLines=0,showPartial=false)
        state.value=state.value.copy(history=listOf(CaptionLine("Older hidden segment"),CaptionLine(long)),
            partial="An unconfirmed partial is hidden by preference")
        show()
        val actual=layout("caption-live-original")
        assertEquals(long,actual.layoutInput.text.text)
        assertTrue("A long caption must wrap rather than clip or scroll horizontally",actual.lineCount>2)
        assertFalse(actual.didOverflowWidth)
        for(index in 0 until actual.lineCount) {
            assertTrue(actual.getLineLeft(index)>=-1f)
            assertTrue(actual.getLineRight(index)<=actual.size.width+1f)
        }
        compose.onNodeWithText("Older hidden segment",substring=true).assertDoesNotExist()
        compose.onNodeWithText("An unconfirmed partial is hidden by preference",substring=true).assertDoesNotExist()
    }

    @Test fun bilingualPendingSourceRemainsAtTheVisibleTailAfterOlderTranslatedText() {
        val olderTranslation="هذا نص مترجم قديم ".repeat(200).trim()
        val latest="Newest source is visible now."
        config.value=config.value.copy(mode=CaptionMode.TRANSLATE,display=CaptionDisplay.BOTH,
            target=TranslationTarget.ARABIC)
        state.value=state.value.copy(history=listOf(CaptionLine("Previously recognized speech.",translation=olderTranslation)))
        show()
        compose.onNodeWithTag("caption-live-translation").assertExists()
        assertTrue("The older target must actually overflow the live viewport",
            layout("caption-live-translation").size.height>400*compose.activity.resources.displayMetrics.density)
        compose.runOnIdle { state.value=state.value.copy(partial=latest) }
        val newest=compose.onNodeWithText(latest).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val older=compose.onNodeWithTag("caption-live-translation").fetchSemanticsNode().boundsInRoot
        assertTrue("The newest source must follow, not precede, the older target paragraph",newest.top>=older.bottom-1f)
        assertEquals("Bilingual tracks must retain the chronological source/target/source order",2,
            compose.onAllNodesWithTag("caption-live-original").fetchSemanticsNodes().size)
    }

    @Test fun heldHistoryKeepsItsScrollAndSnapshotWhileLiveParagraphRemainsBounded() {
        config.value=config.value.copy(minimalHeader=false,tapThrough=false,historyLines=1)
        state.value=state.value.copy(history=(1..140).map { CaptionLine("Saved segment $it.",id=it.toLong()) })
        show()
        val live=layout("caption-live-original").layoutInput.text.text
        assertEquals("Saved segment 139. Saved segment 140.",live)
        compose.onNodeWithText(compose.activity.getString(R.string.ui_read_history_2f89b)).performClick()
        compose.onNodeWithTag("caption-live-original").assertDoesNotExist()
        compose.onNodeWithText("Saved segment 20.").assertDoesNotExist()
        compose.onNodeWithText("Saved segment 21.").assertExists() // Only the latest 120 are retained.
        compose.onNodeWithText("Saved segment 45.").performScrollTo().assertIsDisplayed()
        val before=compose.onNodeWithText("Saved segment 45.").fetchSemanticsNode().boundsInRoot

        compose.runOnIdle {
            state.value=state.value.copy(history=state.value.history+CaptionLine("New speech after holding",id=141))
        }
        compose.onNodeWithText("New speech after holding",substring=true).assertDoesNotExist()
        val after=compose.onNodeWithText("Saved segment 45.").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Incoming speech must not move the held reader",before.top,after.top,1f)
        compose.onNodeWithText(compose.activity.getString(R.string.ui_back_to_live_e83c9)).performClick()
        assertEquals("Saved segment 140. New speech after holding",layout("caption-live-original").layoutInput.text.text)
        compose.onNodeWithText("Saved segment 45.").assertDoesNotExist()
    }
}
