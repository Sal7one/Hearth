package com.sal7one.transiber.translation
import com.sal7one.common_jni.translation.TranslationCatalog
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
class LocalTranslationModelsTest {
 @Test fun importRejectsWrongHashAndNeverPublishesPartialModel() {
  val root = Files.createTempDirectory("translation-import").toFile()
  try {
   val data = "GGUF fixture bytes".toByteArray()
   val valid = TranslationCatalog.models.first().copy(bytes = data.size.toLong(), sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
   val store = LocalTranslationModels(root)
   try { store.import("bad".byteInputStream(), valid); fail("Bad hash") } catch (_: Exception) {}
   assertFalse(store.file(valid).exists()); assertTrue(root.listFiles().orEmpty().isEmpty())
   val installed = store.import(data.inputStream(), valid); assertArrayEquals(data, installed.readBytes())
   assertEquals(installed, store.import(data.inputStream(), valid))
   installed.writeBytes("GGUF fixture bytex".toByteArray())
   assertEquals(installed, store.import(data.inputStream(), valid))
   assertArrayEquals(data, installed.readBytes())
  } finally { root.deleteRecursively() }
 }
}
