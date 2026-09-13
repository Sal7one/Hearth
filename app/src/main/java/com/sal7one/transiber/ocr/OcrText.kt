package com.sal7one.transiber.ocr

import java.text.Normalizer

/** Paddle's Arabic decoder emits visual-order Arabic with intact Latin/number runs. */
internal object OcrText {
    private val latinCharacter = Regex("[a-zA-Z0-9 :*./%+\\-]")

    fun logical(text: String, arabic: Boolean): String {
        if (!arabic) return text
        val groups = mutableListOf<String>()
        val latin = StringBuilder()
        fun flush() {
            if (latin.isEmpty()) return
            val value = latin.toString()
            // Boundary spaces must change sides with the run. Internal spaces in
            // Latin phrases stay within that phrase, and digits keep their order.
            val first = value.indexOfFirst { !it.isWhitespace() }
            if (first == -1) groups += value
            else {
                val last = value.indexOfLast { !it.isWhitespace() }
                if (first > 0) groups += value.substring(0, first)
                groups += value.substring(first, last + 1)
                if (last < value.lastIndex) groups += value.substring(last + 1)
            }
            latin.setLength(0)
        }
        text.codePoints().forEach { point ->
            val character = String(Character.toChars(point))
            if (latinCharacter.matches(character)) latin.append(character)
            else {
                flush()
                groups += character
            }
        }
        flush()
        return Normalizer.normalize(groups.asReversed().joinToString(""), Normalizer.Form.NFKC).trim()
    }
}
