package com.sal7one.transiber.translation

import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One cancellable owner per check/turn. No redirects, retries, logging or source-text fallback. */
internal class TranslationHttpTransport(
    private val enabled: Boolean = ByokPolicy.FEATURE_BYOK,
    private val client: OkHttpClient = sharedClient,
) : AutoCloseable {
    private val calls = mutableSetOf<Call>()
    private var closed = false
    suspend fun execute(request: Request, secret: String = ""): String = suspendCancellableCoroutine { continuation ->
        if (!enabled) { continuation.resumeWithException(IllegalStateException("Cloud translation is unavailable in the offline build.")); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        synchronized(calls) {
            if (closed) { continuation.resumeWithException(IllegalStateException("Cloud translation connection is closed")); return@suspendCancellableCoroutine }
            calls += call
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(calls) { calls -= call }
                if (continuation.isActive) continuation.resumeWithException(IOException(redact(e.message ?: e.toString(), secret)))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        val body = it.body ?: error("HTTP ${it.code}: empty response body")
                        val bytes = body.byteStream().use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(out.size() + count <= 2 * 1024 * 1024) { "Cloud translation response exceeds 2 MiB" }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        check(it.isSuccessful) { "HTTP ${it.code}: ${redact(text, secret)}" }
                        redact(text, secret)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(redact(e.message ?: e.toString(), secret), e))
                } finally { synchronized(calls) { calls -= call } }
            }
        })
    }
    override fun close() { synchronized(calls) { closed = true; calls.forEach { it.cancel() }; calls.clear() } }
    companion object {
        private val sharedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
        private fun redact(value: String, secret: String): String = if (secret.isBlank()) value else value.replace(secret, "[redacted key]")
    }
}

internal class CloudTextTranslator(
    private val connection: CloudTranslationConnection,
    private val languages: CloudTranslationLanguages,
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : CancellableTextTranslator {
    init { check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." } }
    override val id: String get() = connection.provider.label
    override val directions: Set<TranslationDirection> = languages.sourceLanguages.flatMap { from ->
        languages.targetLanguages.filter { to -> languages.supports(from, to) && from != to }.map { TranslationDirection(from, it) }
    }.toSet()
    override suspend fun translate(text: String, direction: TranslationDirection): String {
        val request = CloudTranslationProtocol.translateRequest(connection, text, direction.source, direction.target, languages)
        val raw = transport.execute(request, connection.key)
        val parsed = CloudTranslationProtocol.parseTranslation(connection.provider, raw)
        val translated = if (connection.provider == TextTranslationProvider.GOOGLE)
            androidx.core.text.HtmlCompat.fromHtml(parsed, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        else parsed
        return translated.also { check(it.isNotBlank()) { "${connection.provider.label} returned empty translation" } }
    }
    override fun cancel() = transport.close()
    override fun close() = transport.close()
}
