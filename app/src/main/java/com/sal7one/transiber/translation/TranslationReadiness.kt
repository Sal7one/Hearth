package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import java.io.File

/** Presence and request-contract checks only: never downloads, sends text or opens model weights. */
internal suspend fun checkTranslationSetup(context: Context, localId: String, providerId: String, source: String, target: String): String {
    val knownSource = source !in setOf("auto", "model", "und", "mul", "")
    if (source == target) return "$source → $target · original text (same language)"
    if (providerId != "local") {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." }
        val provider = ConversationTranslationSettings.provider(providerId) ?: error("Unknown translation provider: $providerId")
        val connection = ConversationTranslationSettings.connection(context, provider)
        val languages = ConversationTranslationSettings.capabilities(context, provider) ?: error("Check ${provider.label} languages in Translation settings first.")
        check(target in languages.targetLanguages && (!knownSource || languages.supports(source, target))) { "${provider.label} does not support $source → $target." }
        // Validate the real request contract without sending text or testing billing.
        CloudTranslationProtocol.translateRequest(connection, "Setup check", if (knownSource) source else languages.sourceLanguages.first { languages.supports(it, target) }, target)
        return "${provider.label} · saved connection and supported languages"
    }
    com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "translation")
    if (localId == TranslationOptions.ML_KIT) {
        check(PlatformTranslation.available) { "ML Kit is unavailable in this build. Select an imported local translator." }
        check(target in TranslationOptions.mlKitCodes && (!knownSource || source in TranslationOptions.mlKitCodes)) { "ML Kit does not support $source → $target." }
        val required = if (knownSource) setOf(source, target) else setOf(target)
        val missing = required - PlatformTranslation.installed()
        check(missing.isEmpty()) { "Download ML Kit language packs in Models: ${missing.joinToString()}" }
        return "ML Kit packs installed${if (!knownSource) " · source pack must match detected speech" else ""}"
    }
    check(localId.isNotBlank()) { "Choose a local translator in Live captions → Speech & translation." }
    MarianPackage.find(localId)?.let { pair ->
        check(target == pair.target && (!knownSource || source == pair.source)) {
            "Marian / OPUS-MT ${pair.source} → ${pair.target} does not support $source → $target."
        }
        requireMarianPresence(context, pair)
        return "Marian / OPUS-MT · $source → $target · pinned components present"
    }
    com.sal7one.transiber.translation.MarianCascade.find(localId)?.let { route ->
        check(target == route.target && (!knownSource || source == route.source)) {
            "Marian via English ${route.source} → ${route.target} does not support $source → $target."
        }
        requireMarianPresence(context, route.first)
        requireMarianPresence(context, route.second)
        return "Marian / OPUS-MT · $source → English → $target · both packages present"
    }
    com.sal7one.common_jni.translation.Ct2Catalog.find(localId)?.let { spec ->
        Ct2Package.requireRuntime(context)
        check(target in spec.targets && (!knownSource || spec.supports(source,target))) { "${spec.label} does not support $source → $target" }
        check(com.sal7one.transiber.translation.Ct2Package.installed(context,spec)) { "Install ${spec.label} in Models → Translation" }
        return "${spec.label} · CPU · pinned components present"
    }
    val model = TranslationCatalog.models.firstOrNull { it.id == localId }
        ?: error("Unknown local translation model: $localId. Choose an installed translator in Models → Translation.")
    check(target in model.targetLanguages && (!knownSource || model.supports(source, target))) { "${model.label} does not support $source → $target." }
    check(LocalTranslationModels(File(context.filesDir, "translation-models")).installed().any { it.id == localId }) { "Download or import ${model.label} in Models → Translation." }
    return "${model.label} · $source → $target"
}

/** Registration alone cannot keep a deleted/truncated package ready; full hashes remain at model open. */
private fun requireMarianPresence(context: Context, pair: MarianPackage.Pair) {
    val model = MarianPackage.installed(context, pair)
    check(model != null && File(model.path).isDirectory && pair.parts.all { part ->
        File(model.path, part.fileName).let { it.isFile && it.length() == part.bytes }
    }) { "Download and install Marian ${pair.source} → ${pair.target} in Models → Translation; required model files are missing or incomplete." }
}
