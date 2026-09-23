package com.sal7one.transiber.setup

import com.sal7one.transiber.byok.CloudConfigStore.Provider
import com.sal7one.transiber.byok.CloudConfigStore.SttMode
import com.sal7one.transiber.byok.cloudSpeechKeyRequired
import com.sal7one.transiber.caption.*
import org.junit.Assert.*
import org.junit.Test

class EasySetupPresetTest {
    @Test fun localActivatesBothModelsAndPreservesAppearanceAndAudioChoice() {
        val before=CaptionOverlayConfig(engine=CaptionEngineChoice.CLOUD,textTranslationProviderId="google",
            source=CaptionSource.MIC,theme=CaptionTheme.LIGHT,bubbleHeightDp=230)
        val after=EasySetupPreset.local(before,"speech-installed","marian-en-ar","en","ar")
        assertEquals(CaptionEngineChoice.NEMOTRON,after.engine);assertEquals("speech-installed",after.modelId)
        assertEquals("marian-en-ar",after.localTranslationModelId);assertTrue(after.localTranslationEnabled)
        assertEquals("local",after.textTranslationProviderId);assertEquals("en",after.streamLanguage)
        assertEquals(CaptionTranslationRoute.TEXT_TRANSLATOR,captionTranslationRoute(after,SttMode.BATCH))
        assertEquals(before.source,after.source);assertEquals(before.theme,after.theme);assertEquals(230,after.bubbleHeightDp)
        assertThrows(IllegalArgumentException::class.java) {EasySetupPreset.local(before,"","marian-en-ar","en","ar")}
        assertThrows(IllegalArgumentException::class.java) {EasySetupPreset.local(before,"speech-installed","marian-en-ar","ru","ar")}
        assertThrows(IllegalArgumentException::class.java) {EasySetupPreset.local(before,"speech-installed","marian-en-ar","en","zz")}
        val russian=EasySetupPreset.local(before,"speech-installed","marian-ru-en","ru","en")
        assertEquals("ru",russian.streamLanguage)
        assertEquals("en",russian.target.languageTag)
        val broad=EasySetupPreset.local(before,"speech-installed","hy-mt2-q4","zh","ar")
        assertEquals("zh",broad.streamLanguage)
    }
    @Test fun openAiUsesLiveTranslationAndOtherEndpointsStartWithCaptions() {
        assertEquals(listOf(Provider.OPENAI,Provider.OPENROUTER,Provider.CUSTOM),EasySetupPreset.providers)
        val old=CaptionOverlayConfig(localTranslationEnabled=true,textTranslationProviderId="google")
        val openAi=EasySetupPreset.cloud(old,Provider.OPENAI,"ar")
        assertEquals(CaptionTranslationRoute.LIVE_TARGET,captionTranslationRoute(openAi,SttMode.STREAMING_OPENAI))
        for(provider in listOf(Provider.OPENROUTER,Provider.CUSTOM)) {
            val config=EasySetupPreset.cloud(old,provider,"ar")
            assertEquals(CaptionMode.CAPTIONS,config.mode)
            assertEquals(CaptionTranslationRoute.ORIGINAL,captionTranslationRoute(config,SttMode.BATCH))
        }
    }
    @Test fun savedKeysNeverMoveAcrossProvidersOrEndpoints() {
        assertTrue(EasySetupPreset.canReuseKey(Provider.OPENAI,"https://api.openai.com/v1/",Provider.OPENAI,"https://api.openai.com/v1"))
        assertFalse(EasySetupPreset.canReuseKey(Provider.CUSTOM,"https://server/v1",Provider.OPENAI,"https://api.openai.com/v1"))
        assertFalse(EasySetupPreset.canReuseKey(Provider.CUSTOM,"https://second/v1",Provider.CUSTOM,"https://first/v1"))
        assertFalse(EasySetupPreset.canReuseKey(Provider.CUSTOM,"",Provider.CUSTOM,""))
    }
    @Test fun serverRequiresHttpsWithoutEmbeddedCredentials() {
        assertEquals("https://server.example/v1",EasySetupPreset.endpoint(" https://server.example/v1/ "))
        for(url in listOf("http://server/v1","server/v1","https://user:key@server/v1","https://server/v1?key=x","https://server/v1#key","https:///v1"))
            assertThrows(IllegalArgumentException::class.java) { EasySetupPreset.endpoint(url) }
    }
    @Test fun onlyExplicitBatchServersCanOmitKey() {
        for(mode in SttMode.entries) for(provider in Provider.entries)
            assertEquals(mode!=SttMode.BATCH || provider!=Provider.CUSTOM,cloudSpeechKeyRequired(mode,provider))
    }
}
