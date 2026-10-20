package com.sal7one.hearth.tts.chatterbox

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Remove only 90 absent optional GQA slots; weights, attributes and all other graph bytes stay intact. */
object ChatterboxGraph {
    const val SOURCE_FILENAME = "onnx/language_model_q4.onnx"
    const val FILENAME = "onnx/language_model_q4.ort20.onnx"
    const val SOURCE_BYTES = 228633L
    const val SOURCE_SHA256 = "91846c24706116f87b39aac5b13aa1a67243b6f973bc433327d143b8eff9fde6"
    const val OUTPUT_BYTES = 228453L
    const val OUTPUT_SHA256 = "60f1451649fcef2014208084f546328f3a4e32c635d57983da7dcf75e9e3dbe8"

    fun install(source: File, root: File, cancelled: () -> Unit = {}) {
        cancelled()
        check(source.isFile && source.length() == SOURCE_BYTES) { "Chatterbox graph size mismatch" }
        val raw = source.readBytes()
        check(raw.sha256() == SOURCE_SHA256) { "Chatterbox source graph checksum failed" }
        val converted = trimOmittedGqaInputs(raw, cancelled)
        check(converted.size.toLong() == OUTPUT_BYTES && converted.sha256() == OUTPUT_SHA256) { "Chatterbox derived graph checksum failed" }
        val destination = File(root, FILENAME)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "Cannot create Chatterbox graph directory" }
        val temporary = File.createTempFile("chatterbox-graph-", ".part", destination.parentFile)
        try {
            temporary.writeBytes(converted); cancelled()
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
private data class ProtoField(val tag: Int, val wire: Int, val start: Int, val end: Int, val payload: ByteArray?)

/** Tiny bounded protobuf wire reader; no ONNX/Python dependency and no general graph rewriting API. */
private fun fields(bytes: ByteArray): List<ProtoField> {
    require(bytes.size <= 1_000_000) { "Chatterbox protobuf exceeds bound" }
    var cursor = 0
    fun varint(): Int {
        var value = 0L
        for (shift in 0..28 step 7) {
            require(cursor < bytes.size) { "Truncated Chatterbox protobuf" }
            val b = bytes[cursor++].toInt() and 255
            value = value or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) { require(value <= Int.MAX_VALUE) { "Chatterbox protobuf varint exceeds bound" }; return value.toInt() }
        }
        error("Chatterbox protobuf varint exceeds bound")
    }
    val result = ArrayList<ProtoField>()
    while (cursor < bytes.size) {
        require(result.size < 100_000) { "Chatterbox protobuf field count exceeds bound" }
        val start = cursor; val key = varint(); val tag = key ushr 3; val wire = key and 7
        require(tag > 0) { "Invalid Chatterbox protobuf tag" }
        var payload: ByteArray? = null
        when (wire) {
            0 -> { // Unknown integer fields may use ten-byte signed varints; preserve their exact bytes.
                var done = false
                for (i in 0 until 10) { require(cursor < bytes.size) { "Truncated Chatterbox protobuf scalar" }; if (bytes[cursor++].toInt() and 128 == 0) { done = true; break } }
                require(done) { "Invalid Chatterbox protobuf scalar" }
            }
            1 -> { require(bytes.size - cursor >= 8) { "Truncated protobuf fixed64" }; cursor += 8 }
            2 -> { val length = varint(); require(length <= bytes.size - cursor) { "Truncated protobuf bytes" }; payload = bytes.copyOfRange(cursor, cursor + length); cursor += length }
            5 -> { require(bytes.size - cursor >= 4) { "Truncated protobuf fixed32" }; cursor += 4 }
            else -> error("Unsupported Chatterbox protobuf wire type")
        }
        result += ProtoField(tag, wire, start, cursor, payload)
    }
    return result
}
private fun ByteArrayOutputStream.varint(value: Int) { var rest = value; while (rest >= 128) { write((rest and 127) or 128); rest = rest ushr 7 }; write(rest) }
private fun rebuild(bytes: ByteArray, entries: List<ProtoField>, transform: (ProtoField) -> ByteArray?): ByteArray {
    val out = ByteArrayOutputStream(bytes.size)
    entries.forEach { field ->
        val changed = transform(field)
        when {
            changed == null -> out.write(bytes, field.start, field.end - field.start)
            changed.isEmpty() -> Unit // Used only for omitted input field removal.
            else -> { out.varint((field.tag shl 3) or 2); out.varint(changed.size); out.write(changed) }
        }
    }
    return out.toByteArray()
}
internal fun trimOmittedGqaInputs(raw: ByteArray, cancelled: () -> Unit = {}): ByteArray {
    var graphs = 0; var changedNodes = 0
    val output = rebuild(raw, fields(raw)) { modelField ->
        cancelled()
        if (modelField.tag != 7 || modelField.wire != 2) null else {
            require(++graphs == 1) { "Duplicate Chatterbox model graph" }
            val graph = checkNotNull(modelField.payload)
            rebuild(graph, fields(graph)) { graphField ->
                cancelled()
                if (graphField.tag != 1 || graphField.wire != 2) null else {
                    val node = checkNotNull(graphField.payload); val entries = fields(node)
                    val operator = entries.singleOrNull { it.tag == 4 && it.wire == 2 }?.payload?.toString(Charsets.UTF_8)
                    val domain = entries.singleOrNull { it.tag == 7 && it.wire == 2 }?.payload?.toString(Charsets.UTF_8)
                    if (operator != "GroupQueryAttention" || domain != "com.microsoft") null else {
                        val inputs = entries.filter { it.tag == 1 && it.wire == 2 }
                        require(inputs.size == 12 && inputs.takeLast(3).all { it.payload?.isEmpty() == true }) { "Chatterbox attention contains active unsupported inputs" }
                        changedNodes++
                        var index = 0
                        rebuild(node, entries) { field -> if (field.tag == 1 && field.wire == 2 && index++ >= 9) byteArrayOf() else null }
                    }
                }
            }
        }
    }
    require(graphs == 1 && changedNodes == 30) { "Chatterbox graph attention inventory mismatch" }
    return output
}
