package com.sal7one.transiber.downloads

import java.net.URI

data class DownloadSpec(val url: String, val fileName: String) {
 companion object {
  fun parse(url: String, fileName: String): DownloadSpec {
   val normalized = url.trim()
   val uri = URI(normalized)
   require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "Enter an HTTPS file URL with a valid host" }
   require(uri.rawUserInfo == null) { "Credentials in file URLs are not supported" }
   require(uri.fragment == null) { "Remove the fragment from the file URL" }
   val name = fileName.trim()
   require(name.isNotBlank() && name.length <= 150 && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Enter a filename without folders or control characters (maximum 150 characters)" }
   return DownloadSpec(normalized, name)
  }
 }
}
