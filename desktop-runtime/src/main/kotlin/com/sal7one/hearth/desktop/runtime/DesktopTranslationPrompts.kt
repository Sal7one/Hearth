package com.sal7one.hearth.desktop.runtime

import java.util.Locale

/** Publisher prompt envelopes mirror the Android catalog; chat-control tokens stay in native code. */
internal object DesktopTranslationPrompts {
    fun prompt(text: String, source: String, target: String, family: String): String {
        fun name(code: String) = when (code) {
            "zh" -> "Chinese"; "yue" -> "Cantonese"; "fil" -> "Filipino"
            "auto" -> error("This local translation model needs an explicit source language")
            else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).also { require(it.isNotBlank()) { "Invalid translation language" } }
        }
        val to = name(target)
        return when (family) {
            "eurollm" -> { val from = name(source); "Translate the following $from source text to $to:\n$from: ${text.trim()}\n$to:" }
            "riva-translate" -> {
                require(source == "en" || target == "en") { "Riva supports English-paired translation directions" }
                fun riva(code: String) = when (code) { "es" -> "European Spanish"; "pt" -> "European Portuguese"; "zh" -> "Simplified Chinese"; else -> name(code) }
                "You are an expert at translating text from ${riva(source)} to ${riva(target)}.\n\n${text.trim()}"
            }
            "milmmt-46" -> { val from = name(source); "Translate this from $from to $to:\n$from: ${text.trim()}\n$to:" }
            "translategemma" -> {
                val from = name(source)
                "You are a professional $from ($source) to $to ($target) translator. Your goal is to accurately convey the meaning and " +
                    "nuances of the original $from text while adhering to $to grammar, vocabulary, and cultural sensitivities.\n" +
                    "Produce only the $to translation, without any additional explanations or commentary. Please translate the following $from text into $to:\n\n\n${text.trim()}"
            }
            "index-translate" -> "请将以下文本翻译为$to，直接输出翻译结果，不要进行任何解释。\n\n${text.trim()}"
            "hy-mt2" -> "Translate the following text into $to. Note that you should only output the translated result without any additional explanation:\n\n$text"
            else -> if (source == "zh" || target == "zh") "将以下文本翻译为$to，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
                else "Translate the following segment into $to, without additional explanation.\n\n$text"
        }
    }
}
