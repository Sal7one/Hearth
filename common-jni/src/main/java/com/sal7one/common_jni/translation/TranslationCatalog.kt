package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.SpeechLanguage
import com.sal7one.common_jni.speech.TranslationDirection
import java.util.Locale

/** Publisher-declared coverage, distinct from accuracy measured on a particular device. */
object TranslationLanguages {
    fun normalize(code: String): String = when (val value = SpeechLanguage.normalize(code)) {
        "tl" -> "fil"
        "auto", "mul", "und", "" -> value
        else -> value
    }
    fun label(code: String): String = when(code) {
        "zh" -> "Chinese"; "yue" -> "Cantonese"; "fil" -> "Filipino"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }
    // Initial supported adapter coverage; not a claim of an exhaustive model quality matrix.
    val gemmaLanguages = "ar zh en fr de es pt ru ja ko hi it nl pl tr uk vi id th sv da fi cs ro el hu he fa bg hr sk sl et lv lt".split(' ').toSet()
    val hyLanguages: Set<String> = "zh en fr pt es ja tr ru ar ko th it de vi ms id fil hi pl cs nl km my fa gu ur te mr he bn ta uk bo kk mn ug yue".split(' ').toSet()
}

data class TranslationModelSpec(
    val id: String, val label: String, val family: String, val quantization: String,
    val bytes: Long, val sha256: String, val repo: String, val revision: String, val fileName: String,
) {
    val sourceLanguages get() = if (family == "translategemma") TranslationLanguages.gemmaLanguages else TranslationLanguages.hyLanguages
    val targetLanguages get() = sourceLanguages
    val license get() = when (family) { "translategemma" -> "Gemma terms"; "hy-mt2" -> "Apache-2.0"; else -> "Tencent Hunyuan community license" }
    val directions: Set<TranslationDirection> get() = sourceLanguages.flatMap { a -> targetLanguages.filter { it != a }.map { TranslationDirection(a, it) } }.toSet()
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
    val modelCard get() = "https://huggingface.co/$repo"
    fun supports(source: String, target: String) = TranslationLanguages.normalize(source) in sourceLanguages && TranslationLanguages.normalize(target) in targetLanguages
    fun prompt(text: String, source: String, target: String): String {
        require(text.isNotBlank() && text.length <= 2000) { "Local translation accepts 1–2000 caption characters per line" }
        require(supports(source, target)) { "$label does not support $source → $target" }
        val to = TranslationLanguages.label(TranslationLanguages.normalize(target))
        if (family == "translategemma") {
            val from = TranslationLanguages.label(TranslationLanguages.normalize(source))
            val sourceCode = TranslationLanguages.normalize(source)
            val targetCode = TranslationLanguages.normalize(target)
            return "You are a professional $from ($sourceCode) to $to ($targetCode) translator. Your goal is to accurately convey the meaning and " +
                "nuances of the original $from text while adhering to $to grammar, vocabulary, and cultural sensitivities.\n" +
                "Produce only the $to translation, without any additional explanations or commentary. Please translate the following $from text into $to:\n\n\n${text.trim()}"
        }
        // Publisher's plain translation instruction; chat envelope belongs to the runtime.
        return if (family == "hy-mt2") "Translate the following text into $to. Note that you should only output the translated result without any additional explanation:\n\n$text"
        else if (TranslationLanguages.normalize(source) == "zh" || TranslationLanguages.normalize(target) == "zh")
            "将以下文本翻译为$to，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        else "Translate the following segment into $to, without additional explanation.\n\n$text"
    }
}
object TranslationCatalog {
    val models = listOf(
        TranslationModelSpec("translategemma-4b-q4", "TranslateGemma 4B · Q4_K_M", "translategemma", "Q4_K_M", 2489909760,
            "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330", "mradermacher/translategemma-4b-it-GGUF", "35a7486e128b19642cdc72d7b91b21ba388aaf42", "translategemma-4b-it.Q4_K_M.gguf"),
        TranslationModelSpec("hy-mt15-q4", "HY-MT1.5 1.8B · Q4_K_M", "hy-mt1.5", "Q4_K_M", 1133080512,
            "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7", "tencent/HY-MT1.5-1.8B-GGUF", "265b2e615a7dc9b06c435dc878829ad99a512ba2", "HY-MT1.5-1.8B-Q4_K_M.gguf"),
        TranslationModelSpec("hy-mt15-q6", "HY-MT1.5 1.8B · Q6_K", "hy-mt1.5", "Q6_K", 1474785216,
            "c3819200ab9a79cb29b9a05ce8920e2eb01ae7ce520094fc5b57356494f3c641", "tencent/HY-MT1.5-1.8B-GGUF", "265b2e615a7dc9b06c435dc878829ad99a512ba2", "HY-MT1.5-1.8B-Q6_K.gguf"),
        TranslationModelSpec("hy-mt15-q8", "HY-MT1.5 1.8B · Q8_0", "hy-mt1.5", "Q8_0", 1908528288,
            "6789b06d0902f2f5312c0e1703d56ccbddfcfb6c653d22519b7c720f7db9a98e", "tencent/HY-MT1.5-1.8B-GGUF", "265b2e615a7dc9b06c435dc878829ad99a512ba2", "HY-MT1.5-1.8B-Q8_0.gguf"),
        TranslationModelSpec("hy-mt2-q4", "Hy-MT2 1.8B · Q4_K_M", "hy-mt2", "Q4_K_M", 1133080448,
            "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", "tencent/Hy-MT2-1.8B-GGUF", "a0c709d9fac510f2c807aa3af52872340dc37a4a", "Hy-MT2-1.8B-Q4_K_M.gguf"),
        TranslationModelSpec("hy-mt2-q2", "Hy-MT2 1.8B · Q2_K · experimental", "hy-mt2", "Q2_K", 777483008L,
            "dced2d16784aaf339cab86a469ec3a6963111b200d863ed345a2380842b88ce7", "mradermacher/Hy-MT2-1.8B-GGUF", "d760e9708bbded7cee9aa135b4f1dedb42ed2fc4", "Hy-MT2-1.8B.Q2_K.gguf"),
        TranslationModelSpec("hy-mt2-q3km", "Hy-MT2 1.8B · Q3_K_M", "hy-mt2", "Q3_K_M", 951022336L,
            "11a4e50e70bdb876d4cdf158b47a895fd1bc8173d6ba63d4d6c8261d7cc63756", "mradermacher/Hy-MT2-1.8B-GGUF", "d760e9708bbded7cee9aa135b4f1dedb42ed2fc4", "Hy-MT2-1.8B.Q3_K_M.gguf"),
        TranslationModelSpec("hy-mt2-q6", "Hy-MT2 1.8B · Q6_K", "hy-mt2", "Q6_K", 1474785120,
            "d98fe604dec1f28f58f80d7d560f7177e584d3b8e5835862687660e5ff97cb40", "tencent/Hy-MT2-1.8B-GGUF", "a0c709d9fac510f2c807aa3af52872340dc37a4a", "Hy-MT2-1.8B-Q6_K.gguf"),
        TranslationModelSpec("hy-mt2-q8", "Hy-MT2 1.8B · Q8_0", "hy-mt2", "Q8_0", 1908528192,
            "5c3fe0b1408a5ceb0143184ef247b11b579c525f4b02b060e6c851bb76fef1a4", "tencent/Hy-MT2-1.8B-GGUF", "a0c709d9fac510f2c807aa3af52872340dc37a4a", "Hy-MT2-1.8B-Q8_0.gguf"),
    )
    fun find(id: String): TranslationModelSpec = models.firstOrNull { it.id == id } ?: error("Select an installed local translation model")
}
