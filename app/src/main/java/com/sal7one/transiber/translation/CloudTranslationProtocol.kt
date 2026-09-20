package com.sal7one.transiber.translation

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** API roots, not individual translate URLs. DeepL Pro uses https://api.deepl.com/v2. */
enum class TextTranslationProvider(val id: String, val label: String, val defaultEndpoint: String, val sourceUrl: String) {
    GOOGLE("google", "Google Cloud Translation", "https://translation.googleapis.com/language/translate/v2",
        "https://docs.cloud.google.com/translate/docs/basic/translating-text"),
    AZURE("azure", "Microsoft Azure Translator", "https://api.cognitive.microsofttranslator.com",
        "https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/reference"),
    DEEPL("deepl", "DeepL", "https://api-free.deepl.com/v2",
        "https://developers.deepl.com/api-reference/translate/request-translation"),
    LIBRETRANSLATE("libretranslate", "LibreTranslate", "https://libretranslate.com",
        "https://docs.libretranslate.com/guides/api_usage/");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: error("Unknown text translation provider: $id") }
}

data class CloudTranslationConnection(
    val provider: TextTranslationProvider, val endpoint: String, val key: String, val region: String = "",
) {
    override fun toString() = "CloudTranslationConnection(provider=${provider.id}, key=<redacted>)"
}

/** Maps contain only discovered capabilities. Base app codes retain the exact provider wire code. */
data class CloudTranslationLanguages(
    val sourceCodes: Map<String, String>,
    val targetCodes: Map<String, String>,
    /** Empty means the provider advertises every source/target combination. Libre always supplies edges. */
    val targetsBySource: Map<String, Set<String>> = emptyMap(),
) {
    val sourceLanguages: Set<String> get() = sourceCodes.keys
    val targetLanguages: Set<String> get() = targetCodes.keys
    fun supports(source: String, target: String): Boolean = try {
        val from = CloudTranslationProtocol.normalize(source); val to = CloudTranslationProtocol.normalize(target)
        from != to && from in sourceCodes && to in targetCodes &&
            (targetsBySource.isEmpty() || to in targetsBySource[from].orEmpty())
    } catch (_: IllegalArgumentException) { false }
    fun requirePair(source: String, target: String) {
        require(supports(source, target)) { "This connection does not advertise translation from $source to $target. Refresh supported languages or choose another pair." }
    }
    fun apiSource(code: String): String = sourceCodes[CloudTranslationProtocol.normalize(code)] ?: error("Unsupported source language: $code")
    fun apiTarget(code: String): String = targetCodes[CloudTranslationProtocol.normalize(code)] ?: error("Unsupported target language: $code")
}

/**
 * Pure protocol only; callers must disable HTTP redirects and gate network use by flavor.
 * Never log requests/connection bodies or headers: they contain keys or conversation text.
 * Google authentication header is documented with this exact v2 translate URL:
 * https://docs.cloud.google.com/docs/authentication/api-keys-use
 * Azure languages: https://learn.microsoft.com/en-us/rest/api/translator/translator/languages?view=rest-translator-v3.0
 * DeepL v2 discovery remains documented but deprecated; response order here is source, then target:
 * https://developers.deepl.com/api-reference/languages/retrieve-supported-languages
 * Libre directed targets: https://docs.libretranslate.com/api/operations/languages/
 */
object CloudTranslationProtocol {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val codePattern = Regex("[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8})*")
    fun normalize(code: String): String {
        require(codePattern.matches(code)) { "Invalid language code: $code" }
        return when (val base = code.lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')) {
            "iw" -> "he"; "tl" -> "fil"; "no" -> "nb"; "in" -> "id"; "ji" -> "yi"
            else -> base
        }
    }

    fun sameEndpoint(provider: TextTranslationProvider, first: String, second: String): Boolean = runCatching {
        endpoint(CloudTranslationConnection(provider, first, "")) == endpoint(CloudTranslationConnection(provider, second, ""))
    }.getOrDefault(false)

    fun translateRequest(connection: CloudTranslationConnection, text: String, source: String, target: String,
                         languages: CloudTranslationLanguages? = null): Request {
        require(text.isNotBlank() && text.length <= 5000) { "Translation accepts 1–5000 characters" }
        require(normalize(source) != normalize(target)) { "Choose different source and destination languages" }
        languages?.requirePair(source, target)
        val from = languages?.apiSource(source) ?: defaultCode(connection.provider, source, false)
        val to = languages?.apiTarget(target) ?: defaultCode(connection.provider, target, true)
        val url = endpoint(connection)
        val request = authorized(connection, needsKey = true)
        val body: String
        when (connection.provider) {
            TextTranslationProvider.GOOGLE -> {
                request.url(url)
                body = JSONObject().put("q", JSONArray().put(text)).put("source", from).put("target", to).put("format", "text").toString()
            }
            TextTranslationProvider.AZURE -> {
                request.url(child(url, "translate").newBuilder().addQueryParameter("api-version", "3.0")
                    .addQueryParameter("from", from).addQueryParameter("to", to).addQueryParameter("textType", "plain").build())
                body = JSONArray().put(JSONObject().put("Text", text)).toString()
            }
            TextTranslationProvider.DEEPL -> {
                request.url(child(url, "translate"))
                body = JSONObject().put("text", JSONArray().put(text)).put("source_lang", from).put("target_lang", to).toString()
            }
            TextTranslationProvider.LIBRETRANSLATE -> {
                request.url(child(url, "translate"))
                body = JSONObject().put("q", text).put("source", from).put("target", to).put("format", "text")
                    .apply { if (connection.key.isNotBlank()) put("api_key", connection.key.trim()) }.toString()
            }
        }
        return request.post(body.toRequestBody(jsonType)).build()
    }

    fun languageRequests(connection: CloudTranslationConnection): List<Request> {
        val url = child(endpoint(connection), "languages")
        return when (connection.provider) {
            TextTranslationProvider.GOOGLE -> listOf(authorized(connection, true).url(url).get().build())
            TextTranslationProvider.AZURE -> listOf(Request.Builder().url(url.newBuilder().addQueryParameter("api-version", "3.0")
                .addQueryParameter("scope", "translation").build()).get().build()) // Discovery requires no auth.
            TextTranslationProvider.DEEPL -> listOf("source", "target").map { type ->
                authorized(connection, true).url(url.newBuilder().addQueryParameter("type", type).build()).get().build()
            }
            TextTranslationProvider.LIBRETRANSLATE -> listOf(Request.Builder().url(url).get().build())
        }
    }

    /** Google returned entities are left untouched; Android consumer may decode them once, for Google only. */
    fun parseTranslation(provider: TextTranslationProvider, body: String): String = parse(provider, listOf(body)) {
        val text = when (provider) {
            TextTranslationProvider.GOOGLE -> single(JSONObject(body).getJSONObject("data").getJSONArray("translations")).strictString("translatedText")
            TextTranslationProvider.AZURE -> single(single(JSONArray(body)).getJSONArray("translations")).strictString("text")
            TextTranslationProvider.DEEPL -> single(JSONObject(body).getJSONArray("translations")).strictString("text")
            TextTranslationProvider.LIBRETRANSLATE -> JSONObject(body).strictString("translatedText")
        }
        require(text.isNotBlank()) { "Empty translated text" }
        text
    }

    fun parseLanguages(provider: TextTranslationProvider, bodies: List<String>): CloudTranslationLanguages = parse(provider, bodies) {
        require(bodies.size == if (provider == TextTranslationProvider.DEEPL) 2 else 1) { "Wrong number of language responses" }
        fun list(array: JSONArray, key: String): List<String> {
            require(array.length() in 1..1000) { "Empty or oversized language list" }
            return (0 until array.length()).map { array.getJSONObject(it).strictString(key) }
        }
        when (provider) {
            TextTranslationProvider.GOOGLE -> {
                val rawCodes = list(JSONObject(bodies.single()).getJSONObject("data").getJSONArray("languages"), "language")
                val map = codes(provider, rawCodes, false)
                CloudTranslationLanguages(map, codes(provider, rawCodes, true))
            }
            TextTranslationProvider.AZURE -> {
                val languages = JSONObject(bodies.single()).getJSONObject("translation")
                require(languages.length() in 1..1000)
                val raw = languages.keys().asSequence().toList()
                // A translation scope key is explicitly documented as both a source and a target.
                raw.forEach { languages.getJSONObject(it) }
                CloudTranslationLanguages(codes(provider, raw, false), codes(provider, raw, true))
            }
            TextTranslationProvider.DEEPL -> CloudTranslationLanguages(
                codes(provider, list(JSONArray(bodies[0]), "language"), false),
                codes(provider, list(JSONArray(bodies[1]), "language"), true),
            )
            TextTranslationProvider.LIBRETRANSLATE -> {
                val array = JSONArray(bodies.single())
                val rawSources = list(array, "code")
                require(rawSources.distinct().size == rawSources.size) { "Duplicate LibreTranslate source language" }
                val sourceMap = codes(provider, rawSources, false)
                val rawEdges = (0 until array.length()).associate { i ->
                    val row = array.getJSONObject(i)
                    val targets = row.getJSONArray("targets")
                    require(targets.length() <= 1000)
                    row.strictString("code") to (0 until targets.length()).map { j ->
                        (targets.get(j) as? String ?: error("Invalid target language")).also { normalize(it) }
                    }
                }
                val targetMap = codes(provider, rawEdges.values.flatten(), true)
                // Use only edges belonging to the retained wire source. Never turn this into an all-pairs set.
                val edges = sourceMap.mapValues { (_, wire) ->
                    // Base-code UI cannot represent two wire variants independently. Keep only
                    // edges to the chosen exact target variant, instead of inventing cross-variant support.
                    rawEdges.getValue(wire).filter { targetMap[normalize(it)] == it }.map(::normalize).toSet()
                }
                CloudTranslationLanguages(sourceMap, targetMap, edges)
            }
        }
    }

    private fun codes(provider: TextTranslationProvider, values: List<String>, target: Boolean): Map<String, String> {
        val map = linkedMapOf<String, String>()
        values.sorted().forEach { raw ->
            val normalized = normalize(raw)
            val current = map[normalized]
            if (current == null || preference(provider, normalized, raw, target) > preference(provider, normalized, current, target)) map[normalized] = raw
        }
        return map
    }
    private fun preference(provider: TextTranslationProvider, base: String, raw: String, target: Boolean): Int {
        if (raw.equals(defaultCode(provider, base, target), true)) return 3
        if (raw.equals(base, true)) return 2
        return 1
    }
    /** Used only without a discovered mapping. This maps wire syntax, never claims language support. */
    // Explicit variant choices (when present in discovery): EN-US, PT-PT, ZH-HANS.
    // https://github.com/DeepL/deepl-api-prompts/blob/main/llms.md lists the provider wire conventions.
    private fun defaultCode(provider: TextTranslationProvider, value: String, target: Boolean): String {
        val code = normalize(value)
        return when (provider) {
            TextTranslationProvider.AZURE -> when (code) { "zh" -> "zh-Hans"; "sr" -> "sr-Cyrl"; else -> code }
            TextTranslationProvider.GOOGLE -> when (code) { "zh" -> "zh-CN"; "nb" -> "no"; else -> code }
            TextTranslationProvider.DEEPL -> if (target) when (code) {
                "en" -> "EN-US"; "pt" -> "PT-PT"; "zh" -> "ZH-HANS"; else -> code.uppercase(Locale.ROOT)
            } else code.uppercase(Locale.ROOT)
            TextTranslationProvider.LIBRETRANSLATE -> code
        }
    }
    private fun endpoint(connection: CloudTranslationConnection): HttpUrl {
        // Do not echo a rejected URL; it may contain credentials supplied accidentally.
        val input = connection.endpoint.trim()
        require(input.isNotEmpty() && input.none { it.isWhitespace() || it == '\\' }) { "Enter an HTTPS API root without spaces" }
        val url = input.toHttpUrlOrNull() ?: error("Enter a valid HTTPS API root")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "API root must use HTTPS without credentials, query parameters, or a fragment"
        }
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/').ifBlank { "/" }).build()
    }
    private fun child(root: HttpUrl, path: String) = root.newBuilder().addPathSegment(path).build()
    private fun authorized(connection: CloudTranslationConnection, needsKey: Boolean): Request.Builder {
        val key = connection.key.trim()
        require(key.none { it.code < 32 || it.code == 127 }) { "API key contains a control character" }
        if (needsKey && connection.provider != TextTranslationProvider.LIBRETRANSLATE) require(key.isNotBlank()) { "Enter the ${connection.provider.label} API key" }
        val request = Request.Builder().header("Accept", "application/json")
        when (connection.provider) {
            TextTranslationProvider.GOOGLE -> request.header("X-goog-api-key", key)
            TextTranslationProvider.AZURE -> {
                request.header("Ocp-Apim-Subscription-Key", key)
                if (connection.region.isNotBlank()) {
                    require(connection.region.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Enter a valid Azure resource region" }
                    request.header("Ocp-Apim-Subscription-Region", connection.region)
                }
            }
            TextTranslationProvider.DEEPL -> request.header("Authorization", "DeepL-Auth-Key $key")
            TextTranslationProvider.LIBRETRANSLATE -> Unit
        }
        return request
    }
    private fun single(array: JSONArray): JSONObject {
        require(array.length() == 1) { "Expected one translation result" }
        return array.getJSONObject(0)
    }
    private fun JSONObject.strictString(key: String): String = get(key) as? String ?: error("Invalid $key")
    private inline fun <T> parse(provider: TextTranslationProvider, bodies: List<String>, action: () -> T): T {
        try {
            require(bodies.isNotEmpty() && bodies.all { it.isNotBlank() && it.length <= 1024 * 1024 }) { "Empty or oversized response" }
            bodies.forEach { body ->
                val tokens = JSONTokener(body)
                val parsed = tokens.nextValue()
                require(tokens.nextClean() == '\u0000' && (parsed is JSONObject || parsed is JSONArray)) { "Invalid JSON response" }
                if (parsed is JSONObject) {
                    val objectValue = parsed
                    require(!objectValue.has("error") && !objectValue.has("message")) { "Provider error" }
                }
            }
            return action()
        } catch (e: Exception) {
            // Preserve actual provider body even when status was 200 but its JSON was a failure.
            throw IllegalStateException("${provider.label} response: ${bodies.joinToString("\n")}", e)
        }
    }
}
