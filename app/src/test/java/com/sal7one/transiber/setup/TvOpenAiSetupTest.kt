package com.sal7one.transiber.setup

import android.app.Application
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real encrypted credential/preferences path; no provider calls and no native engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TvOpenAiSetupTest {
    @Test fun tvSetupRespectsOfflineGateAndNeverReusesACustomServerKey() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val actions = EasySetupActions(context)
        if (!ByokPolicy.FEATURE_BYOK) {
            assertThrows(IllegalStateException::class.java) { actions.tvOpenAiKey() }
            try { actions.applyTvOpenAi("unit-test-key"); fail("Offline TV setup accepted a cloud credential") }
            catch (_: IllegalStateException) { }
            assertTrue(context.getSharedPreferences("byok_keys", 0).all.isEmpty())
            assertTrue(context.getSharedPreferences("cloud_voice_v1", 0).all.isEmpty())
            return@runBlocking
        }
        // A key belonging to an arbitrary compatible server must not become an OpenAI key.
        CloudConfigStore.configureSpeech(context, CloudConfigStore.Provider.CUSTOM, "https://private.example/v1", "test-model", CloudConfigStore.SttMode.BATCH)
        assertTrue(ApiKeyStore.setOpenAiKey(context, "unit-test-custom-secret"))
        assertEquals("", actions.tvOpenAiKey())
        try { actions.applyTvOpenAi(""); fail("A custom-server key was silently reused") }
        catch (_: IllegalArgumentException) { }
        assertEquals("https://private.example/v1", CloudConfigStore.baseUrl(context))
        assertEquals("unit-test-custom-secret", ApiKeyStore.getOpenAiKey(context))

        actions.applyTvOpenAi("unit-test-openai-secret")
        assertEquals(CloudConfigStore.Provider.OPENAI, CloudConfigStore.provider(context))
        assertEquals(CloudConfigStore.Provider.OPENAI.baseUrl, CloudConfigStore.baseUrl(context))
        assertEquals(CloudConfigStore.SttMode.STREAMING_OPENAI, CloudConfigStore.sttMode(context))
        val voice = CloudVoiceStore.config(context)
        assertEquals(CloudVoiceProvider.OPENAI, voice.provider)
        assertEquals("unit-test-openai-secret", CloudVoiceStore.key(context, voice))
        assertEquals("unit-test-openai-secret", actions.tvOpenAiKey())
        val saved = CaptionConfigStore.config(context).first()
        assertEquals(CaptionEngineChoice.CLOUD, saved.engine)
        assertEquals(CaptionMode.CAPTIONS, saved.mode)
        assertEquals(CaptionSpeakerChoice.CLOUD, saved.speakerChoice)
        assertFalse(saved.speakCaptions) // Saving setup alone cannot start read-aloud.

        actions.applyTvVoice("nova", true, 60)
        assertEquals("nova", CloudVoiceStore.config(context).voice)
        assertEquals("unit-test-openai-secret", CloudVoiceStore.key(context, CloudVoiceStore.config(context)))
        assertTrue(CaptionConfigStore.config(context).first().speakCaptions)
        assertEquals(60, CaptionConfigStore.config(context).first().speakerVolume)

        // An unreadable official key must surface its error, even if a voice key is available.
        context.getSharedPreferences("byok_keys", 0).edit().putString("openai_api_key_v1", "invalid-envelope").commit()
        val failure = assertThrows(IllegalStateException::class.java) { actions.tvOpenAiKey() }
        assertTrue(failure.message.orEmpty().contains("could not be decrypted"))
    }
}
