package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.CaptionTranslationBridge
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real caption queue + cloud adapter, with an in-process HTTP response and no provider charges. */
class CloudCaptionBridgeTest {
    @Test fun russianCaptionUsesCloudTranslatorAndAttachesToItsOriginalId() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("DeepL-Auth-Key test-key",chain.request().header("Authorization"))
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer)
            val body=org.json.JSONObject(buffer.readUtf8())
            assertEquals("RU",body.getString("source_lang"));assertEquals("AR",body.getString("target_lang"))
            assertEquals("Где станция?",body.getJSONArray("text").getString(0))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"translations":[{"text":"أين المحطة؟"}]}""".toResponseBody()).build()
        }.build()
        val output=CompletableDeferred<Pair<Long,String>>()
        val bridge=CaptionTranslationBridge(this,"ar",open={CloudTextTranslator(
            CloudTranslationConnection(TextTranslationProvider.DEEPL,TextTranslationProvider.DEEPL.defaultEndpoint,"test-key"),
            CloudTranslationLanguages(mapOf("ru" to "RU"),mapOf("ar" to "AR")),TranslationHttpTransport(true,client))},
            result={id,text,_->output.complete(id to text)},notice={if(it!=null)output.completeExceptionally(AssertionError(it))})
        try { assertTrue(bridge.offer(91,"Где станция?","ru"));assertEquals(91L to "أين المحطة؟",withTimeout(5000){output.await()}) }
        finally {bridge.close();bridge.awaitClosed()}
    }
}
