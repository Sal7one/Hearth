package com.sal7one.transiber.settings

import android.app.Application
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.SetupModeStore
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

/** Real basic-mode entry points: these assertions do not start a model or capture audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalMaterial3Api::class)
class SettingsDiscoverabilityTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private lateinit var context: Context
    private var oldMode: String? = null
    private var oldQuery: String? = null

    @Before fun useBasicMode() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        UserPreferences.initialize(context)
        oldMode = UserPreferences.store(context, "hearth-setup").getString("mode", null)
        oldQuery = UserPreferences.store(context, "settings-directory").getString("query", null)
        SetupModeStore.setMode(context, SetupMode.BASIC)
        SettingsDirectoryPreferences.setQuery(context, "")
    }

    @After fun restorePreferences() {
        check(UserPreferences.store(context, "hearth-setup").edit().putString("mode", oldMode).commit())
        check(UserPreferences.store(context, "settings-directory").edit().putString("query", oldQuery).commit())
    }

    @Test fun benchmarkIsVisibleAndOneTapFromNormalSettings() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(initialConfig = CaptionOverlayConfig(), onEasySetup = {}, onAppearance = {},
                    onShortcuts = {}, onFeature = { _, _ -> }, onTranslationModels = {}, onDownloads = {},
                    onBenchmark = { opens++ }, onAdvanced = {}, onHelp = {}, onHistory = {})
            }
        }
        compose.onNodeWithTag("settings-search").assertIsDisplayed()
        compose.onNodeWithTag("settings-benchmark").assertIsDisplayed().performClick()
        assertEquals(1, opens)
    }

    @Test fun quickSettingsBenchmarkNavigatesDirectlyToExistingPageEight() {
        var page: Int? = null
        compose.setContent {
            MaterialTheme {
                RetainedSettingsSheet(visible = true, ready = true) { scroll, sheet ->
                    SettingsQuickSheet(scroll, sheet, CaptionOverlayConfig(), onDismiss = {},
                        onFeature = { _, _ -> }, onTranslationModels = {}, onPage = { page = it }, onReading = {})
                }
            }
        }
        compose.onNodeWithTag("quick-settings-benchmark").assertIsDisplayed().performClick()
        assertEquals(8, page)
    }
}
