package com.sal7one.transiber.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.common_jni.translation.LocalTranslationSession
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared encrypted connection library. The default choice belongs to conversation and typed text. */
internal object ConversationTranslationSettings {
    const val LOCAL = "local"
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("conversation-translators", Context.MODE_PRIVATE)
    fun selected(context: Context): String = if (ByokPolicy.FEATURE_BYOK) prefs(context).getString("selected", LOCAL) ?: LOCAL else LOCAL
    fun provider(id: String): TextTranslationProvider? = TextTranslationProvider.entries.firstOrNull { it.id == id }
    fun localModel(context: Context, fallback: String): String = prefs(context).getString("local-model", null) ?: fallback
    fun select(context: Context, id: String, localId: String? = null) {
        require(id == LOCAL || (ByokPolicy.FEATURE_BYOK && provider(id) != null)) { "Cloud translation is unavailable in the offline build." }
        prefs(context).edit().putString("selected", id).apply {
            if (localId != null) putString("local-model", localId)
        }.apply(); changed()
    }
    /** Explicit user action; does not happen merely by opening a feature or saving a key. */
    suspend fun useEverywhere(context: Context, providerId: String, localId: String) {
        require(providerId == LOCAL || (ByokPolicy.FEATURE_BYOK && provider(providerId) != null)) { "Cloud translation is unavailable in the offline build." }
        com.sal7one.transiber.caption.CaptionConfigStore.update(context) { it.copy(
            localTranslationModelId=localId, textTranslationProviderId=providerId, localTranslationEnabled=true) }
        check(context.getSharedPreferences("camera-translate",0).edit().putString("provider",providerId).putString("local-model",localId).commit()) { "Could not save camera translator" }
        select(context,providerId,localId)
    }
    fun endpoint(context: Context, provider: TextTranslationProvider): String = prefs(context).getString("${provider.id}.endpoint", provider.defaultEndpoint) ?: provider.defaultEndpoint
    fun region(context: Context, provider: TextTranslationProvider): String = prefs(context).getString("${provider.id}.region", "") ?: ""
    fun hasKey(context: Context, provider: TextTranslationProvider): Boolean = prefs(context).contains("${provider.id}.key")
    fun connection(context: Context, provider: TextTranslationProvider): CloudTranslationConnection {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." }
        return CloudTranslationConnection(provider, endpoint(context, provider), readKey(context, provider), region(context, provider))
    }
    @Synchronized
    fun save(context: Context, provider: TextTranslationProvider, endpoint: String, region: String, replacementKey: String?): String {
        check(ByokPolicy.FEATURE_BYOK) { "Cloud translation is unavailable in the offline build." }
        val generation = java.util.UUID.randomUUID().toString()
        val edit = prefs(context).edit().putString("${provider.id}.generation", generation)
        // A new server/key can expose different languages. Only a successful fresh check restores them.
        edit.remove("${provider.id}.languages").putString("${provider.id}.endpoint", endpoint.trim())
            .putString("${provider.id}.region", region.trim())
        val scopedKey = replacementKey ?: if (CloudTranslationProtocol.sameEndpoint(provider, endpoint, endpoint(context, provider))) null else ""
        scopedKey?.let { key ->
            if (key.isBlank()) edit.remove("${provider.id}.key")
            else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey())
                val encrypted = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
                edit.putString("${provider.id}.key", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }
        }
        check(edit.commit()) { "Could not save translation connection" }; changed(); return generation
    }
    @Synchronized
    fun forget(context: Context, provider: TextTranslationProvider) {
        val edit = prefs(context).edit().remove("${provider.id}.key").remove("${provider.id}.languages").remove("${provider.id}.generation")
        if (selected(context) == provider.id) edit.putString("selected", LOCAL)
        check(edit.commit()) { "Could not remove translation key" }; changed()
    }
    fun capabilities(context: Context, provider: TextTranslationProvider): CloudTranslationLanguages? = prefs(context).getString("${provider.id}.languages", null)?.let { raw ->
        try {
            val json = JSONObject(raw)
            fun codes(name: String): Map<String, String> { val obj = json.getJSONObject(name); return obj.keys().asSequence().associateWith { obj.getString(it) } }
            val pairs = json.getJSONObject("pairs")
            CloudTranslationLanguages(codes("sources"), codes("targets"), pairs.keys().asSequence().associateWith { code ->
                val values = pairs.getJSONArray(code); (0 until values.length()).map { values.getString(it) }.toSet()
            })
        } catch (_: Exception) { null } // Corrupt cache requires explicit refresh; never invent capabilities.
    }
    @Synchronized
    fun saveCapabilities(context: Context, provider: TextTranslationProvider, languages: CloudTranslationLanguages, generation: String) {
        check(prefs(context).getString("${provider.id}.generation", null) == generation) {
            "This translation connection changed during language discovery. Check its languages again."
        }
        val pairs = JSONObject(); languages.targetsBySource.forEach { (from, to) -> pairs.put(from, JSONArray(to.toList())) }
        val json = JSONObject().put("sources", JSONObject(languages.sourceCodes)).put("targets", JSONObject(languages.targetCodes)).put("pairs", pairs)
        check(prefs(context).edit().putString("${provider.id}.languages", json.toString()).commit()) { "Could not save supported translation languages" }
        changed()
    }
    fun label(context: Context, localId: String): String = provider(selected(context))?.label ?: TranslationOptions.label(localId)
    fun languages(context: Context, localId: String): Set<String> = provider(selected(context))?.let { p ->
        capabilities(context, p)?.let { it.sourceLanguages + it.targetLanguages }.orEmpty()
    } ?: TranslationOptions.languages(localId)
    fun supports(context: Context, localId: String, source: String, target: String): Boolean {
        if (source == target) return false
        return provider(selected(context))?.let { capabilities(context, it)?.supports(source, target) == true }
            ?: if (localId == TranslationOptions.ML_KIT) source in TranslationOptions.mlKitCodes && target in TranslationOptions.mlKitCodes
            else TranslationCatalog.models.firstOrNull { it.id == localId }?.supports(source, target) == true
    }
    /** Snapshot credentials/capabilities once, before a turn. Later settings cannot reroute in-flight text. */
    fun snapshot(context: Context, localId: String, providerId: String = selected(context)): ConversationTranslatorSnapshot {
        check(ByokPolicy.FEATURE_BYOK || providerId == LOCAL || providerId.isBlank()) { "Cloud translation is unavailable in the offline build." }
        check(providerId == LOCAL || providerId.isBlank() || provider(providerId) != null) { "Unknown translation provider: $providerId" }
        val provider = provider(providerId) ?: return ConversationTranslatorSnapshot(localId)
        return ConversationTranslatorSnapshot(localId, connection(context, provider), checkNotNull(capabilities(context, provider)) { "Check ${provider.label} languages in Translation settings first." })
    }
    private fun readKey(context: Context, provider: TextTranslationProvider): String {
        val value = prefs(context).getString("${provider.id}.key", null) ?: return ""
        val parts = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    private fun masterKey(): SecretKey {
        val alias = "hearth_text_translation_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun changed() { changes.value += 1 }
}

internal data class ConversationTranslatorSnapshot(
    val localId: String,
    val cloud: CloudTranslationConnection? = null,
    val languages: CloudTranslationLanguages? = null,
) {
    val label get() = cloud?.provider?.label ?: TranslationOptions.label(localId)
    fun open(context: Context): CancellableTextTranslator = cloud?.let { CloudTextTranslator(it, checkNotNull(languages)) }
        ?: if (localId == TranslationOptions.ML_KIT) PlatformTranslation.open()
        else TranslationCatalog.find(localId).let { spec -> LocalTranslationSession.open(LocalTranslationModels(File(context.filesDir, "translation-models")).file(spec), spec) }
}
