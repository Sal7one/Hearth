package com.sal7one.transiber.byok

import java.net.HttpURLConnection
import java.net.URL

/** User-triggered speech catalog query, shared by OpenAI/OpenRouter/custom endpoints. */
internal object OpenRouterModelsClient {
    sealed interface Result {
        data class Models(val list: List<SpeechModelCatalog.Model>) : Result
        data class Error(val message: String) : Result
    }

    fun fetch(baseUrl: String, apiKey: String): Result {
        if (!ByokPolicy.FEATURE_BYOK) return Result.Error("Cloud model catalogs are unavailable in FOSS")
        var connection: HttpURLConnection? = null
        return try {
            val base = baseUrl.trim().trimEnd('/')
            val query = if (URL(base).host.equals("openrouter.ai", ignoreCase = true))
                "?output_modalities=transcription" else ""
            connection = (URL("$base/models$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) Result.Error("HTTP $status: $body")
            else Result.Models(SpeechModelCatalog.parse(body))
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString())
        } finally {
            connection?.disconnect()
        }
    }
}
