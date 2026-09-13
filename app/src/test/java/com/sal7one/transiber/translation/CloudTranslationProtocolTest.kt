package com.sal7one.transiber.translation

import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudTranslationProtocolTest {
    private fun connection(provider: TextTranslationProvider, endpoint: String = provider.defaultEndpoint, key: String = "test-secret") =
        CloudTranslationConnection(provider, endpoint, key)
    private fun body(request: Request): String = Buffer().let { buffer -> checkNotNull(request.body).writeTo(buffer); buffer.readUtf8() }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun googleUsesHeaderPlainTextJsonAndExactDiscoveredLanguageCodes() {
        val caps = CloudTranslationProtocol.parseLanguages(TextTranslationProvider.GOOGLE, listOf(
            """{"data":{"languages":[{"language":"iw"},{"language":"en"},{"language":"zh-TW"},{"language":"zh-CN"},{"language":"tl"}]}}"""))
        assertEquals("iw", caps.apiSource("he"))
        assertEquals("tl", caps.apiSource("fil"))
        assertEquals("zh-CN", caps.apiTarget("zh"))
        val text = "line \"one\"\nשלום & <b>two</b>"
        val request = CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE), text, "he", "en", caps)
        assertEquals("https://translation.googleapis.com/language/translate/v2", request.url.toString())
        assertEquals("test-secret", request.header("X-goog-api-key"))
        assertNull(request.url.queryParameter("key"))
        assertEquals(text, JSONObject(body(request)).getJSONArray("q").getString(0))
        assertEquals("text", JSONObject(body(request)).getString("format"))
        assertEquals("iw", JSONObject(body(request)).getString("source"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/json"))
        val languages = CloudTranslationProtocol.languageRequests(connection(TextTranslationProvider.GOOGLE)).single()
        assertEquals("/language/translate/v2/languages", languages.url.encodedPath)
        assertEquals("test-secret", languages.header("X-goog-api-key"))
    }
    @Test fun azureKeepsCustomPathRegionAndDoesNotAuthenticatePublicDiscovery() {
        val c = connection(TextTranslationProvider.AZURE, "https://example.cognitiveservices.azure.com/translator/text/v3.0/").copy(region = "eastus")
        val request = CloudTranslationProtocol.translateRequest(c, "你好", "zh", "iw")
        assertEquals("/translator/text/v3.0/translate", request.url.encodedPath)
        assertEquals("3.0", request.url.queryParameter("api-version"))
        assertEquals("zh-Hans", request.url.queryParameter("from"))
        assertEquals("he", request.url.queryParameter("to"))
        assertEquals("plain", request.url.queryParameter("textType"))
        assertEquals("eastus", request.header("Ocp-Apim-Subscription-Region"))
        assertEquals("test-secret", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("你好", JSONArray(body(request)).getJSONObject(0).getString("Text"))
        val discovery = CloudTranslationProtocol.languageRequests(c).single()
        assertNull(discovery.header("Ocp-Apim-Subscription-Key"))
        assertEquals("translation", discovery.url.queryParameter("scope"))
        val caps = CloudTranslationProtocol.parseLanguages(TextTranslationProvider.AZURE,
            listOf("""{"translation":{"zh-Hant":{},"zh-Hans":{},"ar":{},"he":{}}}"""))
        assertEquals("zh-Hans", caps.apiTarget("zh"))
        assertTrue(caps.supports("ar", "he"))
        assertFalse(caps.supports("ru", "he"))
    }
    @Test fun deeplKeepsSourceAndTargetSeparateAndChoosesAmericanEnglishVariant() {
        val caps = CloudTranslationProtocol.parseLanguages(TextTranslationProvider.DEEPL, listOf(
            """[{"language":"EN"},{"language":"AR"},{"language":"NB"}]""",
            """[{"language":"EN-GB"},{"language":"EN-US"},{"language":"DE"}]"""))
        assertEquals(setOf("en", "ar", "nb"), caps.sourceLanguages)
        assertEquals(setOf("en", "de"), caps.targetLanguages)
        assertEquals("EN-US", caps.apiTarget("en"))
        assertTrue(caps.supports("ar", "en"))
        assertFalse(caps.supports("en", "ar"))
        val requests = CloudTranslationProtocol.languageRequests(connection(TextTranslationProvider.DEEPL))
        assertEquals(listOf("source", "target"), requests.map { it.url.queryParameter("type") })
        assertTrue(requests.all { it.header("Authorization") == "DeepL-Auth-Key test-secret" })
        val request = CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.DEEPL, "https://api.deepl.com/v2"), "مرحبا", "ar", "en", caps)
        assertEquals("https://api.deepl.com/v2/translate", request.url.toString())
        assertEquals("EN-US", JSONObject(body(request)).getString("target_lang"))
        assertEquals("AR", JSONObject(body(request)).getString("source_lang"))
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.DEEPL), "Hi", "en", "ar", caps) }
    }
    @Test fun librePreservesDirectedEdgesAndOptionalKeyWithoutCreatingAllPairs() {
        val caps = CloudTranslationProtocol.parseLanguages(TextTranslationProvider.LIBRETRANSLATE, listOf(
            """[{"code":"en","targets":["ar","es"]},{"code":"ar","targets":["en"]},{"code":"es","targets":[]}]"""))
        assertTrue(caps.supports("en", "ar"))
        assertFalse(caps.supports("ar", "es"))
        assertFalse(caps.supports("es", "en"))
        assertEquals(setOf("ar", "es"), caps.targetsBySource["en"])
        val request = CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.LIBRETRANSLATE, "https://host.example/custom", ""), "Hi", "en", "ar", caps)
        assertEquals("/custom/translate", request.url.encodedPath)
        assertFalse(JSONObject(body(request)).has("api_key"))
        val keyed = CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.LIBRETRANSLATE), "Hi", "en", "ar", caps)
        assertEquals("test-secret", JSONObject(body(keyed)).getString("api_key"))
        assertEquals("https://libretranslate.com/languages", CloudTranslationProtocol.languageRequests(connection(TextTranslationProvider.LIBRETRANSLATE)).single().url.toString())
    }
    @Test fun libreVariantNormalizationDoesNotInventAnUnadvertisedWirePair() {
        val caps = CloudTranslationProtocol.parseLanguages(TextTranslationProvider.LIBRETRANSLATE, listOf(
            """[{"code":"ar","targets":["en-US"]},{"code":"es","targets":["en-GB"]}]"""))
        // One base English choice: the retained exact variant has to be in that source's target list.
        val selectedWire = caps.apiTarget("en")
        assertEquals(selectedWire == "en-US", caps.supports("ar", "en"))
        assertEquals(selectedWire == "en-GB", caps.supports("es", "en"))
    }
    @Test fun parsesExactlyOneNonemptyTranslationAndLeavesGoogleEntityDecodeToAndroid() {
        assertEquals("Tom &amp; Jerry", CloudTranslationProtocol.parseTranslation(TextTranslationProvider.GOOGLE,
            """{"data":{"translations":[{"translatedText":"Tom &amp; Jerry"}]}}"""))
        assertEquals("مرحبا", CloudTranslationProtocol.parseTranslation(TextTranslationProvider.AZURE,
            """[{"translations":[{"text":"مرحبا","to":"ar"}]}]"""))
        assertEquals("Hallo", CloudTranslationProtocol.parseTranslation(TextTranslationProvider.DEEPL, """{"translations":[{"text":"Hallo"}]}"""))
        assertEquals("Hola", CloudTranslationProtocol.parseTranslation(TextTranslationProvider.LIBRETRANSLATE, """{"translatedText":"Hola"}"""))
    }
    @Test fun preservesVerbatimBodiesForProviderErrorsMalformedMissingAndBlankResults() {
        val cases = listOf(
            TextTranslationProvider.GOOGLE to """{"error":{"code":403,"message":"API key not valid."}}""",
            TextTranslationProvider.AZURE to """{"error":{"code":401000,"message":"The request is not authorized."}}""",
            TextTranslationProvider.DEEPL to """{"message":"Quota exceeded"}""",
            TextTranslationProvider.LIBRETRANSLATE to """{"error":"Language pair is not supported"}""",
            TextTranslationProvider.GOOGLE to "bad JSON",
            TextTranslationProvider.LIBRETRANSLATE to """{"translatedText":"Hi"} trailing""",
            TextTranslationProvider.AZURE to "[]",
            TextTranslationProvider.DEEPL to """{"translations":[{"text":""}]}""",
            TextTranslationProvider.LIBRETRANSLATE to """{"translatedText":null}""",
        )
        cases.forEach { (provider, raw) ->
            try { CloudTranslationProtocol.parseTranslation(provider, raw); fail("Expected failure") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains(raw)) }
        }
        val badLanguages = """[{"code":"en"}]"""
        try { CloudTranslationProtocol.parseLanguages(TextTranslationProvider.LIBRETRANSLATE, listOf(badLanguages)); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains(badLanguages)) }
    }
    @Test fun rejectsUnsafeEndpointsCredentialsAndInvalidPairsBeforeRequest() {
        listOf("http://host.example", "https://user:pass@host.example", "https://host.example?key=secret", "https://host.example#fragment",
            "https://host.example\\evil", "https://host.example/a b").forEach { url ->
            rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE, url), "Hi", "en", "ar") }
        }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE, key = ""), "Hi", "en", "ar") }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.DEEPL, key = "key\nInjected"), "Hi", "en", "ar") }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.AZURE).copy(region = "eastus\nkey"), "Hi", "en", "ar") }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE), "Hi", "en", "en-US") }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE), "Hi", "auto", "ar") }
        rejects { CloudTranslationProtocol.translateRequest(connection(TextTranslationProvider.GOOGLE), "", "en", "ar") }
        assertFalse(connection(TextTranslationProvider.GOOGLE).toString().contains("test-secret"))
    }
}
