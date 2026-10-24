package com.sal7one.transiber.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MlKitLanguagePacksTest {
    private val supported = setOf("en", "ar", "ru", "zh", "fil")
    private class Sdk {
        val downloaded = mutableSetOf<String>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        var downloadFailure: Exception? = null
        var lostDownload = false
        fun store(supported: Set<String>) = MlKitLanguagePacks({ supported }, { downloaded.toSet() }, { code, wifi ->
            requests += code to wifi
            downloadFailure?.let { throw it }
            if (!lostDownload) downloaded += code
        }, { downloaded -= it })
    }

    @Test fun englishToArabicRequiresArabicRatherThanAnEnglishDownloadOrUnrelatedRussianPack() = runBlocking {
        val sdk = Sdk(); val store = sdk.store(supported)
        val before = mlKitPackReadiness("en", "ar", supported, store.installed())
        assertEquals(setOf("ar"), before.missing); assertFalse(before.ready)
        store.downloadPair("en", "ar")
        assertEquals(listOf("ar" to true), sdk.requests)
        assertTrue(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
        assertEquals(1L, store.revision.value)
    }

    @Test fun nonEnglishPairDownloadsOnlyMissingSideAndRechecksTheInstalledSdkState() = runBlocking {
        val sdk = Sdk().apply { downloaded += "ar" }; val store = sdk.store(supported)
        store.downloadPair("ru", "ar", wifiOnly = false)
        assertEquals(listOf("ru" to false), sdk.requests)
        assertEquals(setOf("en", "ar", "ru"), store.installed())
        store.downloadPair("ru", "ar")
        assertEquals(1, sdk.requests.size)
    }

    @Test fun sdkCompletionWithoutAnInstalledPackIsFailureAndNeverMakesThePairUsable() = runBlocking {
        val sdk = Sdk().apply { lostDownload = true }; val store = sdk.store(supported)
        try { store.download("ar"); fail("Missing installed file must not become successful setup") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("still unavailable")) }
        assertFalse(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
    }

    @Test fun genuineSdkFailureRemainsVisibleAndAnExplicitRetryCanInstallThePack() = runBlocking {
        val failure = IllegalStateException("SDK storage full")
        val sdk = Sdk().apply { downloadFailure = failure }; val store = sdk.store(supported)
        try { store.download("ar"); fail() } catch (e: IllegalStateException) { assertSame(failure, e) }
        assertFalse(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
        sdk.downloadFailure = null; store.download("ar")
        assertTrue(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
    }

    @Test fun installedDiscoveryNormalizesSdkFilipinoAndNeverInventsUninstalledArabic() = runBlocking {
        val sdk = Sdk().apply { downloaded += setOf("tl", "ru", "unknown") }; val store = sdk.store(supported)
        assertEquals(setOf("en", "fil", "ru"), store.installed())
        assertFalse(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
        assertEquals(setOf("ar", "fil"), mlKitRequiredPacks("ar-SA", "tl", supported))
    }

    @Test fun returningFromBackgroundObservesPackChangesRatherThanCachingAReadyFlag() = runBlocking {
        val sdk = Sdk().apply { downloaded += "ar" }; val store = sdk.store(supported)
        assertTrue(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
        sdk.downloaded.clear()
        assertFalse(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
        store.download("ar"); store.remove("ar")
        assertFalse(mlKitPackReadiness("en", "ar", supported, store.installed()).ready)
    }

    @Test fun anUncheckedUnsupportedOrSameLanguagePairCannotBeSelected() {
        assertFalse(mlKitPackReadiness("en", "ar", supported, null).ready)
        assertFalse(mlKitPackReadiness("en", "xx", supported, setOf("en", "ar")).ready)
        assertFalse(mlKitPackReadiness("en", "en", supported, setOf("en")).ready)
        assertFalse(mlKitPackReadiness("en-US", "en", supported, setOf("en")).ready)
        assertFalse(mlKitPackReadiness("xx", "ar", supported, setOf("ar")).ready)
        assertEquals(setOf("ar"), mlKitPackReadiness("auto", "ar", supported, setOf("en")).missing)
        assertTrue(mlKitPackReadiness("auto", "ar", supported, setOf("en", "ar")).ready)
    }
}
