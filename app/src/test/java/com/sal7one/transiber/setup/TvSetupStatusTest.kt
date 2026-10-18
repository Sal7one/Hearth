package com.sal7one.transiber.setup

import android.app.Application
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TvSetupStatusTest {
    @Test fun dashboardTracksSavedCredentialsAndRemovalWithoutAcceptingCustomEndpoints() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val config = CaptionOverlayConfig(engine = CaptionEngineChoice.CLOUD)
        assertFalse(readTvSetupStatus(context, config).configured)
        if (!ByokPolicy.FEATURE_BYOK) {
            assertTrue(com.sal7one.transiber.preferences.UserPreferences.store(context, "byok_keys").all.isEmpty())
            return@runBlocking
        }
        CloudConfigStore.configureSpeech(context, CloudConfigStore.Provider.OPENAI,
            CloudConfigStore.Provider.OPENAI.baseUrl, "test-model", CloudConfigStore.SttMode.STREAMING_OPENAI)
        assertTrue(ApiKeyStore.setOpenAiKey(context, "unit-test-tv-dashboard-key"))
        assertTrue(readTvSetupStatus(context, config).configured)
        // Optional translation/read-aloud do not force the three setup cards back onto home.
        assertTrue(readTvSetupStatus(context, config.copy(mode = CaptionMode.CAPTIONS, speakCaptions = false)).configured)
        assertTrue(ApiKeyStore.setOpenAiKey(context, ""))
        assertFalse(readTvSetupStatus(context, config).configured)
        com.sal7one.transiber.preferences.UserPreferences.store(context, "byok_keys").edit().putString("openai_api_key_v1", "invalid-envelope").commit()
        val broken = readTvSetupStatus(context, config)
        assertFalse(broken.configured)
        assertTrue(broken.error.orEmpty().contains("could not be decrypted"))
        CloudConfigStore.configureSpeech(context, CloudConfigStore.Provider.CUSTOM,
            "https://private.example/v1", "test-model", CloudConfigStore.SttMode.STREAMING_OPENAI)
        assertFalse(readTvSetupStatus(context, config).configured)
    }
}
