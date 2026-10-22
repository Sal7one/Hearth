package com.sal7one.transiber.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SettingsDirectoryPreferencesTest {
    @Test fun directoryQueryLivesInDataStoreWithoutChangingPipelineValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        UserPreferences.initialize(context)
        val directory = UserPreferences.store(context, "settings-directory")
        val voice = UserPreferences.store(context, "voice-output")
        val original = directory.getString("query", null)
        val originalVoice = voice.all
        try {
            SettingsDirectoryPreferences.setQuery(context, "GPU بنشمارك")
            assertEquals("GPU بنشمارك", SettingsDirectoryPreferences.query(context))
            // The façade is backed by the shared UserPreferences DataStore namespace.
            assertEquals("GPU بنشمارك", UserPreferences.store(context, "settings-directory").getString("query", null))
            assertEquals(originalVoice, voice.all)
            SettingsDirectoryPreferences.setQuery(context, "")
            assertEquals("", SettingsDirectoryPreferences.query(context))
            SettingsDirectoryPreferences.setQuery(context, "x".repeat(1024))
            assertEquals(SettingsDirectoryPreferences.MAX_QUERY_LENGTH, SettingsDirectoryPreferences.query(context).length)
        } finally {
            check(directory.edit().putString("query", original).commit())
        }
    }
}
