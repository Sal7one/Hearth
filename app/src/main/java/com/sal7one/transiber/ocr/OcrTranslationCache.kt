package com.sal7one.transiber.ocr

import java.text.Normalizer

/** Session-local: one OCR language/translator/destination. Coordinates never identify text.
 * Call from the controller's single coroutine dispatcher, outside native inference.
 */
internal class OcrTranslationCache(
    private val maxEntries: Int = 256,
    private val maxCharacters: Int = 96_000,
) {
    private val entries = LinkedHashMap<String, String>(16, .75f, true)
    private var characters = 0
    init { require(maxEntries > 0 && maxCharacters > 0) }
    operator fun get(text: String): String? = entries[key(text)]
    fun put(text: String, translation: String) {
        val key = key(text)
        if (key.isBlank() || translation.isBlank()) return
        val size = key.length + translation.length
        if (size > maxCharacters) return
        entries.remove(key)?.let { characters -= key.length + it.length }
        entries[key] = translation; characters += size
        while (entries.size > maxEntries || characters > maxCharacters) {
            val oldest = entries.entries.iterator().next()
            characters -= oldest.key.length + oldest.value.length
            entries.remove(oldest.key)
        }
    }
    companion object {
        private val whitespace = Regex("[\\s\\p{Z}]+")
        private val cjkGap = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]) (?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])")
        /** Only layout whitespace and canonical Unicode forms; never fuzzy-match words/digits. */
        fun key(text: String): String = whitespace.replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ")
            .trim().let { cjkGap.replace(it, "") }
    }
}
