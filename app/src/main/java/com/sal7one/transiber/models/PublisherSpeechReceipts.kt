package com.sal7one.transiber.models

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Trusted installer state outside the user-importable model tree. No receipt from a ZIP is trusted. */
internal class PublisherSpeechReceipts(private val modelRoot: File) {
    fun matches(id: String, source: SpeechDownload, manifestSha256: String): Boolean {
        val file = file(id)
        if (!file.isFile) return false
        check(file.length() in 1..2048) { "Publisher receipt is oversized or empty" }
        val receipt = JSONObject(file.readText())
        return receipt.getString("archiveSha256") == source.sha256 && receipt.getLong("archiveBytes") == source.bytes &&
            receipt.getString("profile") == source.profile.id && receipt.getString("manifestSha256") == manifestSha256
    }
    fun record(id: String, source: SpeechDownload, manifestSha256: String) {
        require(manifestSha256.matches(Regex("[a-f0-9]{64}")))
        val destination = file(id); check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val pending = File(destination.parentFile, "$id.pending")
        try {
            pending.writeText(JSONObject().put("archiveSha256", source.sha256).put("archiveBytes", source.bytes)
                .put("profile", source.profile.id).put("manifestSha256", manifestSha256).toString())
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    private fun file(id: String): File {
        require(id.matches(Regex("speech-[0-9a-f-]{36}"))) { "Invalid speech receipt identity" }
        return File(modelRoot, ".publisher-receipts/$id.json")
    }
}
