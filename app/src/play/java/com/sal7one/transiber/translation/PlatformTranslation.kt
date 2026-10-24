package com.sal7one.transiber.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK downloads are explicit setup actions. Inference never downloads packs. */
object PlatformTranslation {
    val available = true
    val languages: Set<String> by lazy {
        TranslateLanguage.getAllLanguages().map { if (it == "tl") "fil" else it }.toSet()
    }
    private val manager get() = RemoteModelManager.getInstance()
    private fun sdk(code: String) = if (code == "fil") "tl" else code
    private fun model(code: String) = TranslateRemoteModel.Builder(sdk(code)).build()
    private val packs = MlKitLanguagePacks(
        supported = { languages },
        readInstalled = { manager.getDownloadedModels(TranslateRemoteModel::class.java).waitFor().map { it.language }.toSet() },
        downloadPack = { code, wifiOnly ->
            check(ByokPolicy.FEATURE_BYOK) { "ML Kit packs require the network-enabled app" }
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            manager.download(model(code), conditions).waitFor()
        },
        removePack = { code -> manager.deleteDownloadedModel(model(code)).waitFor() },
    )
    val revision get() = packs.revision
    suspend fun installed(): Set<String> = packs.installed()
    suspend fun download(code: String, wifiOnly: Boolean = true) = packs.download(code, wifiOnly)
    suspend fun downloadPair(source: String, target: String, wifiOnly: Boolean = true) = packs.downloadPair(source, target, wifiOnly)
    suspend fun remove(code: String) = packs.remove(code)
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
