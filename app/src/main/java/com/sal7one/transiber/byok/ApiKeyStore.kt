package com.sal7one.transiber.byok

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Encrypted BYOK store. Each record owns its storage-mode tag; shared key material is never deleted by a provider action. */
object ApiKeyStore {
    private const val PREFS = "byok_keys"
    private const val KEY_ALIAS = "sal7one_byok_master"
    private const val FIELD_OPENAI = "openai_api_key_v1"
    private const val FIELD_GROQ = "groq_api_key_v1"
    private const val FIELD_DEEPGRAM = "deepgram_api_key_v1"
    private const val FIELD_ASSEMBLYAI = "assemblyai_api_key_v1"
    private const val FIELD_MODE = "openai_storage_mode_v1" // Legacy decryption hint only; never overwritten.
    private const val BASIC_KEY_FILE = "byok_basic.key"
    enum class StorageMode { KEYSTORE, BASIC }
    var lastFailure: String? = null
        private set

    fun getOpenAiKey(context: Context): String = getProviderKey(context, FIELD_OPENAI)
    fun setOpenAiKey(context: Context, key: String): Boolean = setProviderKey(context, FIELD_OPENAI, key)
    fun hasOpenAiKey(context: Context): Boolean = getOpenAiKey(context).isNotBlank()

    // ── per-provider streaming keys (same encrypted storage) ──────────────

    fun getGroqKey(context: Context): String =
        getProviderKey(context, FIELD_GROQ)

    fun setGroqKey(context: Context, key: String): Boolean =
        setProviderKey(context, FIELD_GROQ, key)

    fun getDeepgramKey(context: Context): String =
        getProviderKey(context, FIELD_DEEPGRAM)

    fun setDeepgramKey(context: Context, key: String): Boolean =
        setProviderKey(context, FIELD_DEEPGRAM, key)

    fun getAssemblyAiKey(context: Context): String =
        getProviderKey(context, FIELD_ASSEMBLYAI)

    fun setAssemblyAiKey(context: Context, key: String): Boolean =
        setProviderKey(context, FIELD_ASSEMBLYAI, key)

    fun getSonioxKey(context: Context): String = getProviderKey(context, "soniox_key_v1")
    fun setSonioxKey(context: Context, key: String): Boolean = setProviderKey(context, "soniox_key_v1", key)
    fun getElevenLabsKey(context: Context): String = getProviderKey(context, "elevenlabs_key_v1")
    fun setElevenLabsKey(context: Context, key: String): Boolean = setProviderKey(context, "elevenlabs_key_v1", key)

    @Synchronized
    private fun getProviderKey(context: Context, field: String): String {
        lastFailure = null
        val stored = prefs(context)
        val blob = stored.getString(field, null) ?: return ""
        return try {
            val hint = runCatching { ApiKeyCipher.Mode.valueOf(stored.getString(FIELD_MODE, "KEYSTORE")!!) }.getOrNull()
            val read = ApiKeyCipher.decrypt(blob, hint) { mode -> when (mode) {
                ApiKeyCipher.Mode.KEYSTORE -> existingMasterKey()
                ApiKeyCipher.Mode.BASIC -> basicKey(context, create = false)
            } }
            // Rewrap the same ciphertext after a verified read; this also repairs a stale global mode.
            read.migratedEnvelope?.let { stored.edit().putString(field, it).apply() }
            read.value
        } catch (e: Exception) {
            lastFailure = "Saved API key could not be decrypted: ${e.javaClass.simpleName}: ${e.message}"
            "" // Keep the unreadable record for recovery; never replace it with a new encryption key.
        }
    }

    @Synchronized
    private fun setProviderKey(context: Context, field: String, key: String): Boolean {
        lastFailure = null
        if (key.isBlank()) {
            val saved = prefs(context).edit().remove(field).commit()
            if (!saved) lastFailure = "Could not remove saved API key"
            return saved // Other providers may still require either master key and the legacy hint.
        }
        val encrypted = try {
            ApiKeyCipher.encrypt(key.trim(), ApiKeyCipher.Mode.KEYSTORE, masterKey())
        } catch (e: Exception) {
            try {
                ApiKeyCipher.encrypt(key.trim(), ApiKeyCipher.Mode.BASIC, checkNotNull(basicKey(context, create = true)))
                    .also { lastFailure = "${e.javaClass.simpleName}: ${e.message}" }
            } catch (fallback: Exception) {
                lastFailure = "Keystore unavailable (${e.javaClass.simpleName}: ${e.message}); basic protection failed: ${fallback.javaClass.simpleName}: ${fallback.message}"
                return false
            }
        }
        val saved = prefs(context).edit().putString(field, encrypted).commit()
        if (!saved) lastFailure = "Could not save encrypted API key"
        return saved
    }

    fun storageMode(context: Context): StorageMode? {
        getProviderKey(context, FIELD_OPENAI) // A successful legacy read adds the mode to that record.
        val blob = prefs(context).getString(FIELD_OPENAI, null) ?: return null
        return runCatching { ApiKeyCipher.mode(blob)?.let { StorageMode.valueOf(it.name) } }.getOrNull()
    }
    private fun existingMasterKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey
    private fun masterKey(): SecretKey {
        existingMasterKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun basicKey(context: Context, create: Boolean): SecretKey? {
        val file = File(context.filesDir, BASIC_KEY_FILE)
        if (!file.exists()) {
            if (!create) return null
            val material = ByteArray(32).also { SecureRandom().nextBytes(it) }
            // Create a private temporary file, then atomically publish. Never overwrite an existing key.
            val temp = File.createTempFile("byok-key-", ".tmp", context.filesDir)
            try {
                java.nio.file.Files.setPosixFilePermissions(temp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                temp.writeBytes(material)
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
        check(file.length() == 32L) { "Existing basic encryption key is invalid; it was preserved" }
        return SecretKeySpec(file.readBytes(), "AES")
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
