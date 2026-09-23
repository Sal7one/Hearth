package com.sal7one.common_jni.translation
import org.junit.Assert.*
import org.junit.Test
class TranslationCatalogTest {
 @Test fun publisherCatalogHasPinsAndExplicitDirections() {
  assertEquals(TranslationCatalog.models.size, TranslationCatalog.models.map { it.id }.toSet().size)
  TranslationCatalog.models.forEach {
   assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(it.revision.matches(Regex("[0-9a-f]{40}")))
   assertTrue(it.supports("ru-RU", "ar")); assertTrue(it.supports("zh-CN", "en")); if (it.family !in setOf("translategemma", "milmmt-46")) assertTrue(it.supports("tl", "ar"))
   assertFalse(it.supports("auto", "ar")); assertFalse(it.supports("mul", "en")); assertFalse(it.supports("xx", "ar"))
   assertFalse(it.supports("ar", "ar"))
   assertTrue(it.prompt("hello", "en", "ar").contains("hello"))
  }
 }
 @Test fun overlongCaptionIsRejectedBeforeNative() {
  try { TranslationCatalog.models.first().prompt("x".repeat(2001), "en", "ar"); fail("Too long") } catch (_: IllegalArgumentException) {}
 }
 @Test fun milmmtUsesPublishedRawPromptAndCoverage() {
  val spec = TranslationCatalog.find("milmmt-46-1b-q4")
  assertEquals(806057408L, spec.bytes)
  assertEquals(851344832L, TranslationCatalog.find("milmmt-46-1b-q5").bytes)
  assertEquals(spec.revision, TranslationCatalog.find("milmmt-46-1b-q5").revision)
  assertEquals("Gemma terms", spec.license)
  assertTrue(spec.supports("ru-RU", "ar"))
  assertTrue(spec.supports("zh-CN", "en"))
  assertEquals("Translate this from Russian to Arabic:\nRussian: Привет\nArabic:", spec.prompt(" Привет ", "ru", "ar"))
  assertEquals("Translate this from Chinese (Simplified) to English:\nChinese (Simplified): 你好\nEnglish:", spec.prompt("你好", "zh", "en"))
  assertFalse(spec.supports("ug", "ar"))
 }
 @Test fun hyMt2IncludesPinnedLowerFootprintChoicesFromOneImmutableRevision() {
  val variants = TranslationCatalog.models.filter { it.family == "hy-mt2" }
  assertTrue(variants.any { it.id == "hy-mt2-q2" && it.bytes < 800_000_000L })
  assertTrue(variants.any { it.id == "hy-mt2-q3km" && it.bytes < 1_000_000_000L })
  assertEquals(1, variants.filter { it.id in setOf("hy-mt2-q2", "hy-mt2-q3km") }.map { it.revision }.distinct().size)
  assertTrue(variants.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
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
