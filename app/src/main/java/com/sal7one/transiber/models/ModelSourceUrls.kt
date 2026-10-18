package com.sal7one.transiber.models

import java.net.URI

/** Browser links use public HTTPS sources, without credentials embedded in a URL. */
internal fun isSafeModelSourceUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

/** Reading a source must not unexpectedly launch a large model download. */
internal fun modelSourceBrowserUrl(value: String): String {
    if (!isSafeModelSourceUrl(value)) return value
    val uri = URI(value)
    return if (uri.host.equals("huggingface.co", ignoreCase = true)) value.replaceFirst("/resolve/", "/blob/")
    else value
}
