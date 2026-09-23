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
        // Two rows keep memory bounded even for a poor model's long output.
        var previous = Array(hypothesis.size + 1) { Errors(0, 0, it, reference.size) }
        reference.forEachIndexed { ri, expected ->
            val current = Array(hypothesis.size + 1) { Errors(0, ri + 1, 0, reference.size) }
            hypothesis.forEachIndexed { hi, actual ->
                val diagonal = previous[hi]
                current[hi + 1] = if (expected == actual) diagonal else listOf(
                    diagonal.copy(substitutions = diagonal.substitutions + 1),
                    previous[hi + 1].copy(deletions = previous[hi + 1].deletions + 1),
                    current[hi].copy(insertions = current[hi].insertions + 1),
                ).minBy { it.substitutions + it.deletions + it.insertions }
            }
            previous = current
        }
        return previous.last()
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
