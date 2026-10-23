package com.sal7one.transiber.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.ocr.OcrLanguageControls
import com.sal7one.transiber.ocr.OcrPreferences
import com.sal7one.transiber.ocr.OcrSelection
import com.sal7one.transiber.ocr.rememberOcrPreferences
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise the real standalone library, not a supplied test-only onSelect callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="en-rUS-w400dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class OcrLibrarySelectionTest {
    @get:Rule val compose=AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) {it.activity}
    private lateinit var context: Context
    private lateinit var previous: OcrSelection
    private var oldFamily: String?=null
    private var oldQuery: String?=null
    private var oldInstalled=false

    @Before fun prepare()=runBlocking {
        context=ApplicationProvider.getApplicationContext()
        UserPreferences.initialize(context)
        previous=OcrPreferences(context).read()
        OcrPreferences(context).update {OcrSelection()}
        val browse=UserPreferences.store(context,"model-explorer")
        oldFamily=browse.getString("ocr-family",null);oldQuery=browse.getString("ocr-query",null)
        oldInstalled=browse.getBoolean("ocr-installed",false)
        check(browse.edit().putString("ocr-family","manga").putString("ocr-query","Koharu")
            .putBoolean("ocr-installed",false).commit())
    }
    @After fun restore() {
        if(::context.isInitialized) {
            OcrPreferences(context).update {previous}
            check(UserPreferences.store(context,"model-explorer").edit().putString("ocr-family",oldFamily)
                .putString("ocr-query",oldQuery).putBoolean("ocr-installed",oldInstalled).commit())
        }
    }

    @Test fun choosingKoharuInStandaloneLibraryPersistsTheReaderConsumedByCameraAndOverlay() {
        compose.setContent {MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {SettingsOcrLocalUi()}
        }}
        compose.onNodeWithTag("ocr-profile-choose-manga-koharu-clean-page").performScrollTo().performClick()
        compose.onNodeWithTag("ocr-profile-choose-manga-koharu-clean-page").assertIsSelected()
        compose.runOnIdle {
            // Both platform entry points create their own OcrPreferences instance.
            val camera=OcrPreferences(context).read()
            val overlay=OcrPreferences(context).read()
            assertEquals("manga-koharu-clean-page",camera.profileId)
            assertEquals(camera,overlay);assertEquals("ja",camera.source);assertEquals("ar",camera.target)
        }
        compose.onNodeWithTag("ocr-selected-reader").assertTextContains("Koharu",substring=true)
    }

    @Test fun directModeControlsPreserveDestinationAndDoNotSubstituteAnotherReader() {
        OcrPreferences(context).update {it.withProfile("manga-koharu-clean-page")}
        compose.setContent {MaterialTheme {
            val (selection,store)=rememberOcrPreferences()
            OcrLanguageControls(selection,setOf("ar"),"Fixture translator",{next ->store.update {next}}, {})
        }}
        compose.onNodeWithTag("ocr-mode-manga-koharu-clean-page").assertIsSelected()
        compose.onNodeWithTag("ocr-mode-manga-koharu-page").performClick().assertIsSelected()
        compose.runOnIdle {
            val reopened=OcrPreferences(context).read()
            assertEquals("manga-koharu-page",reopened.withSource("ja").profileId)
            assertEquals("ar",reopened.target)
        }
    }
}
