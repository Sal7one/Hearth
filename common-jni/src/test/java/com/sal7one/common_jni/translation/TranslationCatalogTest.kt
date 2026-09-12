package com.sal7one.common_jni.translation
import org.junit.Assert.*
import org.junit.Test
class TranslationCatalogTest {
 @Test fun publisherCatalogHasPinsAndExplicitDirections() {
  assertEquals(TranslationCatalog.models.size, TranslationCatalog.models.map { it.id }.toSet().size)
  TranslationCatalog.models.forEach {
   assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(it.revision.matches(Regex("[0-9a-f]{40}")))
   assertTrue(it.supports("ru-RU", "ar")); assertTrue(it.supports("zh-CN", "en")); if (it.family != "translategemma") assertTrue(it.supports("tl", "ar"))
   assertFalse(it.supports("auto", "ar")); assertFalse(it.supports("mul", "en")); assertFalse(it.supports("xx", "ar"))
   assertTrue(it.prompt("hello", "en", "ar").endsWith("\n\nhello"))
  }
 }
 @Test fun overlongCaptionIsRejectedBeforeNative() {
  try { TranslationCatalog.models.first().prompt("x".repeat(2001), "en", "ar"); fail("Too long") } catch (_: IllegalArgumentException) {}
 }
    @org.junit.Test fun gemmaUsesItsOwnDirectionsAndTemplate() {
        val spec = TranslationCatalog.find("translategemma-4b-q4")
        org.junit.Assert.assertTrue(spec.supports("ru", "ar"))
        org.junit.Assert.assertFalse(spec.supports("ug", "ar"))
        val prompt = spec.prompt("  Привет  ", "ru", "ar")
        org.junit.Assert.assertTrue(prompt.startsWith("You are a professional Russian (ru) to Arabic (ar) translator."))
        org.junit.Assert.assertTrue(prompt.endsWith("\n\n\nПривет"))
        org.junit.Assert.assertTrue(TranslationCatalog.find("hy-mt15-q4").supports("ug", "ar"))
    }
}
