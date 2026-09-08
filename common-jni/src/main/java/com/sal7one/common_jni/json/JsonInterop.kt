package com.sal7one.common_jni.json

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Strictly typed helpers for JSON crossing the Kotlin/native boundary.
 *
 * Benchmark note (JNI roundtrip measurement): this object is pure-JVM — it
 * uses `org.json` to validate and coerce values but never performs a JNI call
 * itself. The actual Kotlin↔native JSON roundtrip happens in the engine/native
 * methods (e.g. `CommonJni` / `stt_jni.cpp`), not here, so a JNI roundtrip
 * measurement is not trivially instrumentable at this layer and is therefore
 * skipped. The JVM-only prepare/parse cost can still be approximated with
 * `PerfMetrics.measure { JsonInterop.asciiString(value) }` or
 * `objectOrNull(text)`, but that is a JVM roundtrip, not a JNI one.
 */
internal object JsonInterop {
    private const val HEX = "0123456789abcdef"
    private const val MAX_INPUT_CHARS = 1024 * 1024
    private const val MAX_DEPTH = 64

    /** JSON text safe for JNI modified-UTF-8 APIs: all non-ASCII UTF-16 units are escaped. */
    fun asciiString(value: Any): String {
        val text = value.toString()
        text.forEachIndexed { index, character ->
            require(!character.isHighSurrogate() ||
                (index + 1 < text.length && text[index + 1].isLowSurrogate())) {
                "JSON contains an unpaired high surrogate"
            }
            require(!character.isLowSurrogate() ||
                (index > 0 && text[index - 1].isHighSurrogate())) {
                "JSON contains an unpaired low surrogate"
            }
        }
        return buildString {
            text.forEach { character ->
                if (character.code <= 0x7f) {
                    append(character)
                } else {
                    append("\\u")
                    append(HEX[(character.code ushr 12) and 0x0f])
                    append(HEX[(character.code ushr 8) and 0x0f])
                    append(HEX[(character.code ushr 4) and 0x0f])
                    append(HEX[character.code and 0x0f])
                }
            }
        }
    }

    fun objectOrNull(document: String): JSONObject? = parseDocument(document) as? JSONObject

    fun arrayOrNull(document: String): JSONArray? = parseDocument(document) as? JSONArray

    private fun parseDocument(document: String): Any? {
        if (!hasBoundedNesting(document)) return null
        return try {
            val tokener = JSONTokener(document)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else value
        } catch (_: Exception) {
            null
        }
    }

    private fun hasBoundedNesting(document: String): Boolean {
        if (document.length > MAX_INPUT_CHARS) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (character in document) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return !inString && depth == 0
    }

    fun stringOrNull(value: JSONObject, key: String): String? =
        if (!value.has(key) || value.isNull(key)) null else value.opt(key) as? String

    fun boolOrDefault(value: JSONObject, key: String, default: Boolean): Boolean {
        val raw = if (value.has(key) && !value.isNull(key)) value.opt(key) else null
        return raw as? Boolean ?: default
    }

    fun intOrDefault(value: JSONObject, key: String, default: Int): Int {
        val number = numberOrNull(value, key) ?: return default
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) {
            return default
        }
        return double.toInt()
    }

    fun longOrDefault(value: JSONObject, key: String, default: Long): Long =
        longOrNull(value, key) ?: default

    fun longOrNull(value: JSONObject, key: String): Long? {
        val number = numberOrNull(value, key) ?: return null
        return when (number) {
            is Byte, is Short, is Int, is Long -> number.toLong()
            else -> {
                val double = number.toDouble()
                val maxExactInteger = 9_007_199_254_740_991.0
                if (!double.isFinite() || double % 1.0 != 0.0 ||
                    double < -maxExactInteger || double > maxExactInteger) {
                    null
                } else {
                    double.toLong()
                }
            }
        }
    }

    fun floatOrDefault(value: JSONObject, key: String, default: Float): Float {
        val double = numberOrNull(value, key)?.toDouble() ?: return default
        return if (double.isFinite() && double >= -Float.MAX_VALUE && double <= Float.MAX_VALUE) {
            double.toFloat()
        } else {
            default
        }
    }

    fun doubleOrNull(value: JSONObject, key: String): Double? {
        val double = numberOrNull(value, key)?.toDouble() ?: return null
        return double.takeIf(Double::isFinite)
    }

    fun arrayOrNull(value: JSONObject, key: String): JSONArray? =
        if (!value.has(key) || value.isNull(key)) null else value.opt(key) as? JSONArray

    fun objectOrNull(value: JSONArray, index: Int): JSONObject? = value.opt(index) as? JSONObject

    private fun numberOrNull(value: JSONObject, key: String): Number? =
        if (!value.has(key) || value.isNull(key)) null else value.opt(key) as? Number
}
