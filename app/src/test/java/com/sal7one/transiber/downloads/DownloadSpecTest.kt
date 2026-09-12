package com.sal7one.transiber.downloads
import org.junit.Assert.*
import org.junit.Test
class DownloadSpecTest {
 @Test fun destinationsAreVisibleUniqueAndContained() {
  val spec = DownloadSpec.parse("https://example.org/model", "model.gguf")
  assertEquals("Real time transiber/models/job-1-model.gguf", spec.destination(true, "job-1"))
  assertEquals("Real time transiber/files/job-2-model.gguf", spec.destination(false, "job-2"))
  assertNotEquals(spec.destination(true, "a"), spec.destination(true, "b"))
  assertThrows(IllegalArgumentException::class.java) { spec.destination(true, "../escape") }
  assertThrows(IllegalArgumentException::class.java) { DownloadSpec(spec.url, "../escape").destination(true, "a") }
 }
 @Test fun preservesSignedQuery() { assertEquals("https://example.org/model?token=a%2Fb&x=1", DownloadSpec.parse(" https://example.org/model?token=a%2Fb&x=1 ", "model.zip").url) }
 @Test fun rejectsUnsafeNames() { listOf("../x", "a/b", "a\\b", ".", "..", "", "a\nfile").forEach { name -> assertThrows(IllegalArgumentException::class.java) { DownloadSpec.parse("https://example.org/a", name) } } }
 @Test fun rejectsCredentialsAndNonHttps() { listOf("http://example.org/a", "file:///etc/a", "https://user:pass@example.org/a", "https:///a", "https://example.org/a#x").forEach { url -> assertThrows(IllegalArgumentException::class.java) { DownloadSpec.parse(url, "a.zip") } } }
}
