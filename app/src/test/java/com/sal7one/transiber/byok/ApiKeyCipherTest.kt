package com.sal7one.transiber.byok

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ApiKeyCipherTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun eachProviderCarriesItsOwnModeAndRandomIv() {
        val hardware = key(); val basic = key()
        val first = ApiKeyCipher.encrypt("provider A fixture", ApiKeyCipher.Mode.KEYSTORE, hardware)
        val again = ApiKeyCipher.encrypt("provider A fixture", ApiKeyCipher.Mode.KEYSTORE, hardware)
        val second = ApiKeyCipher.encrypt("provider B fixture", ApiKeyCipher.Mode.BASIC, basic)
        assertNotEquals(first, again)
        val load: (ApiKeyCipher.Mode) -> SecretKey = { if (it == ApiKeyCipher.Mode.KEYSTORE) hardware else basic }
        assertEquals("provider A fixture", ApiKeyCipher.decrypt(first, ApiKeyCipher.Mode.BASIC, load).value)
        assertEquals("provider B fixture", ApiKeyCipher.decrypt(second, ApiKeyCipher.Mode.KEYSTORE, load).value)
        assertFalse(first.contains("provider A fixture"))
    }
    @Test fun legacyGlobalModeCanBeWrongWithoutLosingOtherProvider() {
        val hardware = key(); val basic = key()
        val original = ApiKeyCipher.encrypt("legacy fixture", ApiKeyCipher.Mode.BASIC, basic)
        val legacy = original.split(':').takeLast(2).joinToString(":")
        val read = ApiKeyCipher.decrypt(legacy, ApiKeyCipher.Mode.KEYSTORE) { if (it == ApiKeyCipher.Mode.KEYSTORE) hardware else basic }
        assertEquals("legacy fixture", read.value)
        assertEquals(original, read.migratedEnvelope)
        assertEquals(ApiKeyCipher.Mode.BASIC, read.mode)
        assertFalse(read.toString().contains("legacy fixture"))
    }
    @Test fun modernRecordsNeverTryAnotherStorageMode() {
        val original = ApiKeyCipher.encrypt("fixture", ApiKeyCipher.Mode.KEYSTORE, key())
        val modes = mutableListOf<ApiKeyCipher.Mode>()
        assertTrue(runCatching { ApiKeyCipher.decrypt(original, ApiKeyCipher.Mode.BASIC) { modes += it; null } }.isFailure)
        assertEquals(listOf(ApiKeyCipher.Mode.KEYSTORE), modes)
    }
    @Test fun missingOrCorruptedKeysFailWithoutWritingReplacementMaterial() {
        val original = ApiKeyCipher.encrypt("fixture", ApiKeyCipher.Mode.BASIC, key())
        assertTrue(runCatching { ApiKeyCipher.decrypt(original, null) { null } }.isFailure)
        assertTrue(runCatching { ApiKeyCipher.decrypt(original, null) { key() } }.isFailure)
        assertTrue(runCatching { ApiKeyCipher.decrypt("v2:BASIC:AA==:AA==", null) { key() } }.isFailure)
    }
    @Test fun alteredCiphertextIsRejectedInsteadOfReturningCorruptCredentials() {
        val basic = key()
        val original = ApiKeyCipher.encrypt("fixture", ApiKeyCipher.Mode.BASIC, basic)
        val fields = original.split(':').toMutableList()
        val bytes = java.util.Base64.getDecoder().decode(fields.last())
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        fields[3] = java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { ApiKeyCipher.decrypt(fields.joinToString(":"), null) { basic } }.isFailure)
    }
}
