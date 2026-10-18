package com.sal7one.transiber.models

import com.sal7one.common_jni.language.LanguageCatalog
import java.text.Normalizer
import java.util.Locale

/** Search presentation metadata only; never changes an engine's supported languages. */
internal object ModelSearch {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val languageAliases = mapOf(
        "zh" to "Mandarin Chinese 中文 普通话 简体中文",
        "yue" to "Cantonese Chinese 粵語 粤语 廣東話 广东话",
        "fa" to "Farsi Persian فارسی",
        "my" to "Burmese Myanmar",
        "fil" to "Filipino Tagalog",
        "tl" to "Tagalog Filipino",
        "nb" to "Norwegian Bokmal Bokmål",
        "no" to "Norwegian Bokmal Bokmål",
        "he" to "Hebrew עברית",
        "ar" to "Arabic عربي العربية",
    )
    private val languageCache = mutableMapOf<Pair<String, String>, String>()

    private fun normalize(value: String): String = separators.replace(
        marks.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "")
            .replace('ى', 'ي').replace("ـ", "").lowercase(Locale.ROOT), " "
    ).trim()

    /** Every word may match a different field, e.g. "whisper Arabic q5". */
    fun matches(query: String, vararg fields: String): Boolean {
        val words = normalize(query).split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return true
        val searchable = normalize(fields.joinToString(" "))
        val exact = searchable.split(' ').toSet()
        return words.all { word -> if (word.length <= 2) word in exact else searchable.contains(word) }
    }

    /** Native, English and current UI language names are useful in the same search. */
    fun languageTerms(codes: Collection<String>): String {
        val locale = Locale.getDefault()
        return codes.joinToString(" ") { code ->
            synchronized(languageCache) {
                languageCache.getOrPut(code to locale.toLanguageTag()) {
                    val option = LanguageCatalog.option(code)
                    val language = Locale.forLanguageTag(code)
                    listOf(code, option.nativeName, option.englishName,
                        language.getDisplayName(locale), language.getDisplayName(Locale.ENGLISH),
                        language.getDisplayName(Locale.forLanguageTag("ar")), language.getDisplayName(Locale.SIMPLIFIED_CHINESE),
                        languageAliases[code.substringBefore('-')].orEmpty()).joinToString(" ")
                }
            }
        }
    }
}
