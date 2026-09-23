package com.sal7one.transiber.downloads

/** Additional bytes required for a transfer and its atomic installation, not total model RAM. */
data class DownloadBudget(val remainingDownload: Long, val installation: Long, val reserve: Long = 64L * 1024 * 1024) {
    init { require(remainingDownload >= 0 && installation >= 0 && reserve >= 0) }
    val sameVolumeRequired: Long get() = Math.addExact(Math.addExact(remainingDownload, installation), reserve)
    val privateVolumeRequired: Long get() = Math.addExact(installation, reserve)
    companion object {
        fun estimate(bytes: Long, installedBytes: Long?, downloaded: Long = 0): DownloadBudget {
            require(bytes > 0 && downloaded in 0..bytes)
            // Archives without expanded metadata are explicitly labelled estimates in the UI.
            return DownloadBudget(bytes - downloaded, installedBytes ?: Math.multiplyExact(bytes, 4))
        }
    }
}

/** Validates a resume before any bytes are appended. A server ignoring Range restarts safely. */
object DownloadResume {
    data class Response(val offset: Long, val total: Long)
    fun response(code: Int, requestedOffset: Long, contentRange: String?, contentLength: Long,
                 expectedBytes: Long?, savedValidator: String?, responseValidator: String?): Response {
        require(requestedOffset >= 0)
        if (code == 200) {
            if (expectedBytes != null && contentLength >= 0) require(contentLength == expectedBytes) {
                "Download size differs from the selected model: $contentLength, expected $expectedBytes"
            }
            return Response(0, expectedBytes ?: contentLength)
        }
        require(code == 206) { "HTTP $code: download range was rejected" }
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
            ?: error("Invalid Content-Range: $contentRange")
        val (start, end, total) = match.destructured.toList().map { it.toLongOrNull() ?: error("Content-Range overflow") }
        require(start == requestedOffset && end >= start && end < total) { "Download range does not match saved offset" }
        require(contentLength < 0 || contentLength == end - start + 1) { "Download range length does not match response" }
        require(expectedBytes == null || total == expectedBytes) { "Download total changed: $total, expected $expectedBytes" }
        require(savedValidator == null || responseValidator == null || savedValidator == responseValidator) { "Download changed while paused; restart this download" }
        return Response(start, total)
    }
}
