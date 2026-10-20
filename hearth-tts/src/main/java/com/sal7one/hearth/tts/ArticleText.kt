package com.sal7one.hearth.tts

/** Lazy article chunking; Arabic punctuation and surrogate pairs remain intact. No translation or rewriting. */
object ArticleText {
    const val MAXIMUM_CHARACTERS = 200_000
    fun chunks(text: String, limit: Int = 180): Sequence<String> {
        require(text.isNotBlank() && text.length <= MAXIMUM_CHARACTERS) { "Articles must contain 1–$MAXIMUM_CHARACTERS characters" }
        require(limit in 32..1000)
        return sequence {
            var start = 0
            while (start < text.length) {
                while (start < text.length && text[start].isWhitespace()) start++
                if (start == text.length) break
                var end = minOf(start + limit, text.length)
                if (end < text.length) {
                    if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                    var sentence = -1
                    var word = -1
                    for (index in start until end) {
                        if (text[index].isWhitespace()) word = index
                        if (index >= start + limit / 3 && text[index] in "\n؟!。！？؛") sentence = index + 1
                        if (index >= start + limit / 3 && text[index] == '.' &&
                            text.getOrNull(index + 1)?.isWhitespace() == true && text.getOrNull(index - 1)?.isDigit() != true)
                            sentence = index + 1
                    }
                    end = sentence.takeIf { it > start } ?: word.takeIf { it > start } ?: end
                }
                val part = text.substring(start, end).trim()
                if (part.isNotEmpty()) yield(part)
                start = end
            }
        }
    }
}
