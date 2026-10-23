package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.settings.SettingsSheetRestorationTest
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

/** Exercise the real catalogue selection and DataStore boundary, without downloading weights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BenchmarkBulkSelectionTest {
    @get:Rule val compose = AndroidComposeTestRule(SettingsSheetRestorationTest.RobolectricActivityRule()) { it.activity }
    private lateinit var context: Context
    private var previous: Set<String>? = null
    private val key = "test-bulk-selection"

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        UserPreferences.initialize(context)
        val preferences = UserPreferences.store(context, "benchmark-options")
        previous = preferences.getStringSet(key, null)
        check(preferences.edit().remove(key).commit())
    }

    @After fun restore() {
        check(UserPreferences.store(context, "benchmark-options").edit().putStringSet(key, previous).commit())
    }

    @Test fun selectingFiveHundredModelsPersistsAfterLeavingAndReopeningTheCatalogue() {
        val entries = List(500) { index -> BenchmarkCatalogueEntry(
            SuggestedModel("model-$index", "Model $index", 1024L, "speech"), "Fixture", setOf("en")) }
        var open by mutableStateOf(true)
        var observed = emptyList<String>()
        compose.setContent {
            MaterialTheme {
                if (open) {
                    var selected by rememberBenchmarkSelection(key)
                    SideEffect { observed = selected }
                    BenchmarkCatalogueDialog(entries, "en", "", emptySet(), emptyList(), selected, { selected = it },
                        1024L * 1024 * 1024, false, "", emptyList(), {}, {}, {}, {}, { open = false })
                }
            }
        }
        compose.onNodeWithText("Select shown").performClick()
        compose.runOnIdle {
            assertEquals(500, observed.size)
            assertEquals(entries.map { it.model.id }.toSet(), UserPreferences.store(context, "benchmark-options").getStringSet(key, emptySet()))
            open = false
        }
        compose.runOnIdle { observed = emptyList(); open = true }
        compose.runOnIdle { assertEquals(500, observed.size) }
        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), observed)
            assertEquals(emptySet<String>(), UserPreferences.store(context, "benchmark-options").getStringSet(key, null))
        }
    }
}
