package com.sal7one.transiber.byok

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned per-key envelope; old iv:ciphertext records remain readable without replacing key material. */
internal object ApiKeyCipher {
    enum class Mode { KEYSTORE, BASIC }
    class Read(val value: String, val mode: Mode, val migratedEnvelope: String?) {
        override fun toString() = "ApiKeyCipher.Read(mode=$mode, value=<redacted>)"
    }
    fun encrypt(value: String, mode: Mode, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore must generate its own encryption IV; a caller-supplied IV is rejected.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return pack(mode, cipher.iv, cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    fun mode(blob: String): Mode? = if (blob.startsWith("v2:")) Mode.valueOf(blob.split(':', limit = 4)[1]) else null
    fun decrypt(blob: String, legacyHint: Mode?, existingKey: (Mode) -> SecretKey?): Read {
        val fields = blob.split(':')
        val declared = mode(blob)
        require(fields.size == if (declared == null) 2 else 4) { "Invalid saved API key envelope" }
        val iv = Base64.getDecoder().decode(fields[fields.size - 2])
        val encrypted = Base64.getDecoder().decode(fields.last())
        require(iv.size == 12 && encrypted.size >= 16) { "Invalid saved API key envelope" }
        val candidates = declared?.let(::listOf) ?: (listOfNotNull(legacyHint) + Mode.entries).distinct()
        var failure: Exception? = null
        for (mode in candidates) {
            try {
                val key = existingKey(mode) ?: continue // Reads never generate keys or overwrite files.
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val value = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
                return Read(value, mode, if (declared == null) pack(mode, iv, encrypted) else null)
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("Encryption key for the saved API key is unavailable")
    }
    private fun pack(mode: Mode, iv: ByteArray, data: ByteArray): String =
        "v2:${mode.name}:${Base64.getEncoder().encodeToString(iv)}:${Base64.getEncoder().encodeToString(data)}"
}
