package com.sal7one.transiber.byok

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted storage for user-supplied API keys (BYOK).
 *
 * Two layers, in order of preference:
 *  1. Android Keystore AES-256-GCM — hardware-backed where available.
 *  2. Basic protection — a random 256-bit key kept in a mode-0600 file in
 *     the app's private dir, used with the same AES-GCM scheme. Engaged
 *     only when the device Keystore is genuinely unavailable or its entry
 *     is broken (Samsung/older ROM quirks); the UI reports which layer is
 *     active, honestly.
 *
 * NETWORK CODE (play distribution only — see [ByokPolicy]).
 */
object ApiKeyStore {

    private const val PREFS = "byok_keys"
    private const val KEY_ALIAS = "sal7one_byok_master"
    private const val FIELD_OPENAI = "openai_api_key_v1"
    private const val FIELD_GROQ = "groq_api_key_v1"
    private const val FIELD_DEEPGRAM = "deepgram_api_key_v1"
    private const val FIELD_ASSEMBLYAI = "assemblyai_api_key_v1"
    private const val FIELD_MODE = "openai_storage_mode_v1"
    private const val BASIC_KEY_FILE = "byok_basic.key"

    private const val KEY_SIZE_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    enum class StorageMode { KEYSTORE, BASIC }

    var lastFailure: String? = null
        private set

    fun getOpenAiKey(context: Context): String {
        val blob = prefs(context).getString(FIELD_OPENAI, null) ?: return ""
        return runCatching { decrypt(context, blob) }.getOrDefault("")
    }

    /**
     * Store (or clear, when [key] is blank) the provider API key.
     * Returns true when stored. Failures expose their reason via
     * [lastFailure]; the storage layer falls back before failing.
     */
    fun setOpenAiKey(context: Context, key: String): Boolean {
        lastFailure = null
        val editor = prefs(context).edit()
        if (key.isBlank()) {
            editor.remove(FIELD_OPENAI).remove(FIELD_MODE).apply()
            deleteBasicKey(context)
            return true
        }
        val trimmed = key.trim()

        // Layer 1: Android Keystore (with one regenerate-retry for broken
        // entries left behind by OS updates / key invalidation).
        var stored = tryStoreKeystore(context, trimmed)
        if (!stored) {
            val reason = lastFailure
            runCatching { deleteKeystoreEntry(context) }
            stored = tryStoreKeystore(context, trimmed)
            if (!stored && reason != null) {
                // Layer 2: basic file-backed key, honestly reported.
                lastFailure = null
                stored = tryStoreBasic(context, trimmed)
                if (!stored) {
                    lastFailure = "Keystore unavailable ($reason) and basic fallback failed: $lastFailure"
                }
            }
        }
        return stored
    }

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

    private fun getProviderKey(context: Context, field: String): String {
        val blob = prefs(context).getString(field, null) ?: return ""
        return runCatching { decrypt(context, blob) }.getOrDefault("")
    }

    private fun setProviderKey(context: Context, field: String, key: String): Boolean {
        lastFailure = null
        val editor = prefs(context).edit()
        if (key.isBlank()) {
            editor.remove(field).apply()
            return true
        }
        val stored = tryStoreKeystore(context, key.trim(), field)
        if (stored) return true
        val reason = lastFailure
        runCatching { deleteKeystoreEntry(context) }
        val retry = tryStoreKeystore(context, key.trim(), field)
        if (retry) return true
        val basic = tryStoreBasic(context, key.trim(), field)
        if (basic) return true
        lastFailure = "Keystore unavailable ($reason) and basic fallback failed: $lastFailure"
        return false
    }

    fun storageMode(context: Context): StorageMode? {
        return runCatching {
            StorageMode.valueOf(prefs(context).getString(FIELD_MODE, "") ?: "")
        }.getOrNull()
    }

    // ── keystore layer ──────────────────────────────────────────────────────

    private fun tryStoreKeystore(context: Context, key: String, field: String = FIELD_OPENAI): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(context), GCMParameterSpec(GCM_TAG_BITS, newIv()))
        val ct = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(field, pack(cipher.iv, ct))
            .putString(FIELD_MODE, StorageMode.KEYSTORE.name)
            .apply()
        true
    } catch (e: Exception) {
        lastFailure = "${e.javaClass.simpleName}: ${e.message}"
        Log.e("ApiKeyStore", "Keystore store failed", e)
        false
    }

    private fun masterKey(context: Context): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKeystoreEntry(context: Context) {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
            Log.w("ApiKeyStore", "Deleted broken keystore entry; will regenerate")
        }
    }

    // ── basic (fallback) layer ──────────────────────────────────────────────

    private fun tryStoreBasic(context: Context, key: String, field: String = FIELD_OPENAI): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, basicKey(context), GCMParameterSpec(GCM_TAG_BITS, newIv()))
        val ct = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(field, pack(cipher.iv, ct))
            .putString(FIELD_MODE, StorageMode.BASIC.name)
            .apply()
        true
    } catch (e: Exception) {
        lastFailure = "${e.javaClass.simpleName}: ${e.message}"
        Log.e("ApiKeyStore", "Basic store failed", e)
        false
    }

    private fun basicKey(context: Context): SecretKey {
        val file = File(context.filesDir, BASIC_KEY_FILE)
        val bytes = if (file.exists() && file.length() == 32L) {
            file.readBytes()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { material ->
                file.writeBytes(material)
                // Owner-only access; other app UIDs cannot read it.
                runCatching {
                    java.nio.file.Files.setPosixFilePermissions(
                        file.toPath(),
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    )
                }
            }
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun deleteBasicKey(context: Context) {
        File(context.filesDir, BASIC_KEY_FILE).delete()
    }

    // ── shared codec ────────────────────────────────────────────────────────

    private fun newIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    private fun pack(iv: ByteArray, ct: ByteArray): String =
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)

    private fun decrypt(context: Context, blob: String): String {
        val (ivB64, ctB64) = blob.split(':', limit = 2)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val mode = runCatching {
            StorageMode.valueOf(prefs(context).getString(FIELD_MODE, StorageMode.KEYSTORE.name)!!)
        }.getOrDefault(StorageMode.KEYSTORE)
        val secret = if (mode == StorageMode.BASIC) basicKey(context) else masterKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
