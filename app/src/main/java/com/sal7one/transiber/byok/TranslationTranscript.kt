package com.sal7one.transiber.byok

/** Continuous translation deltas have no input-turn boundaries. Segment output itself. */
internal class TranslationTranscript(private val maxChars: Int = 180) {
    init { require(maxChars >= 4) }
    private val pending = StringBuilder()
    val partial: String get() = pending.toString().trim()

    fun append(delta: String): List<String> {
        pending.append(delta) // whitespace-only deltas separate words
        val lines = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val punctuation = pending.indexOfFirst { it in ".!?。！？\n" }
            var cut = if (punctuation >= 0 && punctuation < maxChars) punctuation + 1 else 0
            if (cut == 0 && pending.length > maxChars) {
                cut = pending.substring(0, maxChars).lastIndexOf(' ').takeIf { it > maxChars / 2 } ?: maxChars
                if (Character.isHighSurrogate(pending[cut - 1])) cut--
            }
            if (cut == 0) break
            pending.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let(lines::add)
            pending.delete(0, cut)
        }
        return lines
    }

    fun flush(): String = partial.also { pending.setLength(0) }
}
