package com.sal7one.transiber.settings

import com.sal7one.common_jni.translation.Ct2Catalog
import org.junit.Assert.*
import org.junit.Test

class Ct2BrowserTest {
    @Test fun directModelsRespectDirectionAndSharedCompactFilters() {
        val en=Ct2Catalog.find("ct2-quickmt-en-ar")!!
        assertTrue(ct2BrowserMatches(en,"Arabic","en","ar","compact"))
        assertFalse(ct2BrowserMatches(en,"","ar","en","compact"))
        assertFalse(ct2BrowserMatches(en,"","en","ar","text"))
        assertTrue(ct2BrowserMatches(en,"العربية","any","any","any"))
        assertFalse(ct2BrowserMatches(en,"unrelated-model","any","any","any"))
        val small=Ct2Catalog.find("ct2-small100-int8")!!
        assertTrue(ct2BrowserMatches(small,"CTranslate2","ja","ar","compact"))
        assertFalse(ct2BrowserMatches(small,"","ar","ar","compact"))
    }
}
