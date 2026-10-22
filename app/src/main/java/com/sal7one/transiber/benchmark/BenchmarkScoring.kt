package com.sal7one.transiber.benchmark

import java.text.Normalizer
import java.util.Locale

/** Reference similarity only. This is deliberately independent of Android and model runtimes. */
internal object BenchmarkScoring {
    const val NORMALIZATION = "nfc-lower-punctuation-space-v1"
    const val CHRF_PARAMETERS = "sentence-macro-chrF++-beta2-char1..6-word1..2-case-sensitive-v1"
    data class Errors(val substitutions: Int, val deletions: Int, val insertions: Int, val referenceUnits: Int) {
        val rate: Double? get() = if (referenceUnits > 0) (substitutions + deletions + insertions).toDouble() / referenceUnits
            else if (insertions == 0) 0.0 else null
    }
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}]"), " ").trim().replace(Regex("\\s+"), " ")
    fun wordErrors(reference: String, hypothesis: String): Errors = edits(words(normalize(reference)), words(normalize(hypothesis)))
    fun characterErrors(reference: String, hypothesis: String): Errors = edits(
        normalize(reference).filterNot(Char::isWhitespace).codePoints().toArray().toList(),
        normalize(hypothesis).filterNot(Char::isWhitespace).codePoints().toArray().toList())
    private fun words(value: String) = if (value.isBlank()) emptyList() else value.split(' ')
    private fun <T> edits(reference: List<T>, hypothesis: List<T>): Errors {
        require(reference.size <= 8000 && hypothesis.size <= 8000) { "Reference scoring text exceeds 8000 units" }
        // Primitive rolling rows avoid allocating several Errors objects per edit cell. This
        // matters when hundreds of phone runs score long transcripts (up to 64M cells).
        var previousS = IntArray(hypothesis.size + 1)
        var previousD = IntArray(hypothesis.size + 1)
        var previousI = IntArray(hypothesis.size + 1) { it }
        var currentS = IntArray(hypothesis.size + 1)
        var currentD = IntArray(hypothesis.size + 1)
        var currentI = IntArray(hypothesis.size + 1)
        reference.forEachIndexed { ri, expected ->
            currentS[0] = 0; currentD[0] = ri + 1; currentI[0] = 0
            hypothesis.forEachIndexed { hi, actual ->
                val column = hi + 1
                if (expected == actual) {
                    currentS[column] = previousS[hi]; currentD[column] = previousD[hi]; currentI[column] = previousI[hi]
                } else {
                    val substitution = previousS[hi] + previousD[hi] + previousI[hi] + 1
                    val deletion = previousS[column] + previousD[column] + previousI[column] + 1
                    val insertion = currentS[hi] + currentD[hi] + currentI[hi] + 1
                    // Preserve the original deterministic tie order: substitute, delete, insert.
                    when {
                        substitution <= deletion && substitution <= insertion -> {
                            currentS[column] = previousS[hi] + 1; currentD[column] = previousD[hi]; currentI[column] = previousI[hi]
                        }
                        deletion <= insertion -> {
                            currentS[column] = previousS[column]; currentD[column] = previousD[column] + 1; currentI[column] = previousI[column]
                        }
                        else -> {
                            currentS[column] = currentS[hi]; currentD[column] = currentD[hi]; currentI[column] = currentI[hi] + 1
                        }
                    }
                }
            }
            var swap = previousS; previousS = currentS; currentS = swap
            swap = previousD; previousD = currentD; currentD = swap
            swap = previousI; previousI = currentI; currentI = swap
        }
        return Errors(previousS.last(), previousD.last(), previousI.last(), reference.size)
    }
    /** Sentence chrF++ with effective order, matching SacreBLEU word_order=2 defaults. */
    fun chrf(reference: String, hypothesis: String): Double {
        val refChars = reference.filterNot(Char::isWhitespace).codePoints().toArray().map(Int::toString)
        val hypChars = hypothesis.filterNot(Char::isWhitespace).codePoints().toArray().map(Int::toString)
        val refWords = punctuationWords(reference); val hypWords = punctuationWords(hypothesis)
        var precision = 0.0; var recall = 0.0; var orders = 0
        for (order in 1..8) {
            val n = if (order <= 6) order else order - 6
            val r = grams(if (order <= 6) refChars else refWords, n)
            val h = grams(if (order <= 6) hypChars else hypWords, n)
            val refCount = r.values.sum(); val hypCount = h.values.sum()
            if (refCount > 0 && hypCount > 0) {
                val matched = h.entries.sumOf { (key, count) -> minOf(count, r[key] ?: 0) }
                precision += matched.toDouble() / hypCount; recall += matched.toDouble() / refCount; orders++
            }
        }
        if (orders == 0) return if (reference.isEmpty() && hypothesis.isEmpty()) 100.0 else 0.0
        precision /= orders; recall /= orders
        return if (precision + recall == 0.0) 0.0 else 500.0 * precision * recall / (4.0 * precision + recall)
    }
    private val punctuation = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toSet()
    private fun punctuationWords(text: String): List<String> = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty).flatMap { word ->
        when {
            word.length == 1 -> listOf(word)
            word.first() in punctuation -> listOf(word.first().toString(), word.drop(1))
            word.last() in punctuation -> listOf(word.dropLast(1), word.last().toString())
            else -> listOf(word)
        }
    }
    private fun grams(tokens: List<String>, n: Int): Map<List<String>, Int> =
        if (tokens.size < n) emptyMap() else (0..tokens.size - n).map { tokens.subList(it, it + n) }.groupingBy { it.toList() }.eachCount()
}
