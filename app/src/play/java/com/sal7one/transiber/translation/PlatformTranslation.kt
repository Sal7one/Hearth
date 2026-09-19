package com.sal7one.transiber.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK downloads are explicit setup actions. Inference never downloads packs. */
object PlatformTranslation {
    val available = true
    private val manager get() = RemoteModelManager.getInstance()
    private fun sdk(code: String) = if (code == "fil") "tl" else code
    private fun model(code: String) = TranslateRemoteModel.Builder(sdk(code)).build()
    suspend fun installed(): Set<String> = manager.getDownloadedModels(TranslateRemoteModel::class.java).waitFor().map { if (it.language == "tl") "fil" else it.language }.toSet() + "en"
    suspend fun download(code: String) {
        require(code in TranslationOptions.mlKitCodes)
        if (code != "en") manager.download(model(code), DownloadConditions.Builder().requireWifi().build()).waitFor()
    }
    suspend fun remove(code: String) {
        require(code in TranslationOptions.mlKitCodes)
        if (code != "en") manager.deleteDownloadedModel(model(code)).waitFor()
    }
    fun open(): CancellableTextTranslator = Session()
    private class Session : CancellableTextTranslator {
        override val id = TranslationOptions.ML_KIT
        override val directions = TranslationOptions.mlKitCodes.flatMap { a -> TranslationOptions.mlKitCodes.filter { it != a }.map { TranslationDirection(a, it) } }.toSet()
        private val cancelled = AtomicBoolean(false)
        private var client: Translator? = null
        private var pair: TranslationDirection? = null
        override suspend fun translate(text: String, direction: TranslationDirection): String {
            check(!cancelled.get()) { "ML Kit translation cancelled" }
            require(direction in directions) { "ML Kit does not support ${direction.source} → ${direction.target}" }
            val missing = setOf(direction.source, direction.target) - installed()
            check(missing.isEmpty()) { "Download ML Kit language packs in Models: ${missing.joinToString()}. Original text is preserved." }
            check(!cancelled.get()) { "ML Kit translation cancelled" }
            if (pair != direction) {
                client?.close()
                client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(sdk(direction.source)).setTargetLanguage(sdk(direction.target)).build())
                pair = direction
            }
            val translated = checkNotNull(client).translate(text).waitFor()
            check(!cancelled.get()) { "ML Kit translation cancelled" }
            return translated.also { check(it.isNotBlank()) { "ML Kit returned empty translation" } }
        }
        override fun cancel() { cancelled.set(true) }
        // The bridge closes only after the suspended call has unwound.
        override fun close() { cancel(); client?.close(); client = null }
    }
}
private suspend fun <T> Task<T>.waitFor(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
