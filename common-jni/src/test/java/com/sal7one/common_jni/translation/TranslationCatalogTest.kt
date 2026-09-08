package com.sal7one.common_jni.translation
import org.junit.Assert.*
import org.junit.Test
class TranslationCatalogTest {
 @Test fun publisherCatalogHasPinsAndExplicitDirections() {
  assertEquals(6, TranslationCatalog.models.size)
  TranslationCatalog.models.forEach {
   assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue(it.revision.matches(Regex("[0-9a-f]{40}")))
   assertTrue(it.supports("ru-RU", "ar")); assertTrue(it.supports("zh-CN", "en")); assertTrue(it.supports("tl", "ar"))
   assertFalse(it.supports("auto", "ar")); assertFalse(it.supports("mul", "en")); assertFalse(it.supports("xx", "ar"))
   assertTrue(it.prompt("hello", "en", "ar").endsWith("\n\nhello"))
  }
 }
 @Test fun overlongCaptionIsRejectedBeforeNative() {
  try { TranslationCatalog.models.first().prompt("x".repeat(2001), "en", "ar"); fail("Too long") } catch (_: IllegalArgumentException) {}
 }
}
