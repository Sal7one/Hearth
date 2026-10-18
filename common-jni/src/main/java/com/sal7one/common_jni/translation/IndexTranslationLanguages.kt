package com.sal7one.common_jni.translation

import java.util.Locale

/** Publisher inventory: Index-Translate technical report, Appendix C (2026-09-30).
 * Script/region entries stay distinct. Coverage is a publisher claim, not a quality score.
 */
object IndexTranslationLanguages {
    private data class Entry(val code: String, val publisherCodes: String, val name: String)
    private val entries = """
        en|en, eng_Latn|English
        zh|zh, zho_Hans|Chinese
        de|de|German
        fr|fr|French
        es|es|Spanish
        ja|ja|Japanese
        ko|ko|Korean
        pt|pt|Portuguese
        ru|ru|Russian
        ar|ar|Arabic
        it|it|Italian
        nl|nl|Dutch
        pl|pl|Polish
        ro|ro|Romanian
        sv|sv|Swedish
        tr|tr|Turkish
        hi|hi|Hindi
        vi|vi|Vietnamese
        th|th|Thai
        id|id|Indonesian
        ms|ms|Malay
        fil|fil|Filipino
        uk|ukr_Cyrl|Ukrainian
        fa|fas_Arab, pes_Arab|Persian
        cs|ces_Latn|Czech
        el|ell_Grek|Greek
        da|dan_Latn|Danish
        hu|hun_Latn|Hungarian
        fi|fin_Latn|Finnish
        nb|nob_Latn|Norwegian Bokmål
        sk|slk_Latn|Slovak
        bg|bul_Cyrl|Bulgarian
        bs|bos_Latn|Bosnian
        ca|cat_Latn|Catalan
        bn|ben_Beng|Bengali
        he|heb_Hebr|Hebrew
        lt|lit_Latn|Lithuanian
        sl|slv_Latn|Slovenian
        et|ekk_Latn|Estonian
        sq|als_Latn|Albanian
        lv|lvs_Latn|Latvian
        az|azj_Latn|Azerbaijani
        hr|hrv_Latn|Croatian
        ta|tam_Taml|Tamil
        ne|npi_Deva|Nepali
        ur|urd_Arab|Urdu (Arabic script)
        mk|mkd_Cyrl|Macedonian
        sr|srp_Cyrl|Serbian (Cyrillic)
        mr|mar_Deva|Marathi
        ka|kat_Geor|Georgian
        ml|mal_Mlym|Malayalam
        kk|kaz_Cyrl|Kazakh
        is|isl_Latn|Icelandic
        gl|glg_Latn|Galician
        kn|kan_Knda|Kannada
        ary|ary_Arab|Moroccan Arabic
        gu|guj_Gujr|Gujarati
        be|bel_Cyrl|Belarusian
        af|afr_Latn|Afrikaans
        hy|hye_Armn|Armenian
        mn|khk_Cyrl|Mongolian
        km|khm_Khmr|Khmer
        eu|eus_Latn|Basque
        my|mya_Mymr|Burmese
        la|lat_Latn|Latin
        uz-Cyrl|uzn_Cyrl|Uzbek (Cyrillic)
        te|tel_Telu|Telugu
        or|ory_Orya|Odia
        nn|nno_Latn|Norwegian Nynorsk
        uz|uzn_Latn|Uzbek (Latin)
        sw|swh_Latn|Swahili
        si|sin_Sinh|Sinhala
        ky|kir_Cyrl|Kyrgyz
        so|som_Latn|Somali
        pa|pan_Guru|Punjabi
        arz|arz_Arab|Egyptian Arabic
        cy|cym_Latn|Welsh
        nrm|nrm_Latn|Norman
        ps|pbt_Arab|Pashto
        ga|gle_Latn|Irish
        sr-Latn|srp_Latn|Serbian (Latin)
        ha|hau_Latn|Hausa
        ckb|ckb_Arab|Central Kurdish
        mt|mlt_Latn|Maltese
        yue|yue_Hani, yue_Hant|Cantonese
        tg|tgk_Cyrl|Tajik
        ku|kmr_Latn|Northern Kurdish
        bew|bew_Latn|Betawi
        am|amh_Ethi|Amharic
        lo|lao_Laoo|Lao
        lb|ltz_Latn|Luxembourgish
        fy|fry_Latn|Frisian
        dv|div_Thaa|Dhivehi
        eo|epo_Latn|Esperanto
        rw|kin_Latn|Kinyarwanda
        ars|ars_Arab|Najdi Arabic
        fo|fao_Latn|Faroese
        mg|plt_Latn|Malagasy
        as|asm_Beng|Assamese
        sd|snd_Arab|Sindhi
        xh|xho_Latn|Xhosa
        tk|tuk_Latn|Turkmen
        ht|hat_Latn|Haitian Creole
        gd|gla_Latn|Scottish Gaelic
        ceb|ceb_Latn|Cebuano
        yi|ydd_Hebr|Yiddish
        jv|jav_Latn|Javanese
        ba|bak_Cyrl|Bashkir
        pap|pap_Latn|Papiamento
        mi|mri_Latn|Māori
        ug|uig_Arab|Uyghur
        tt|tat_Cyrl|Tatar
        bo|bod_Tibt|Tibetan
        mww|mww_Latn|White Hmong
        hyw|hyw_Armn|Western Armenian
        yo|yor_Latn|Yoruba
        zu|zul_Latn|Zulu
        sdh|sdh_Arab|Southern Kurdish
        sm|smo_Latn|Samoan
        co|cos_Latn|Corsican
        hif|hif_Latn|Fiji Hindi
        su|sun_Latn|Sundanese
        ny|nya_Latn|Chichewa
        ig|ibo_Latn|Igbo
        haw|haw_Latn|Hawaiian
        st|sot_Latn|Sesotho
        lus|lus_Latn|Mizo
        sn|sna_Latn|Shona
        cv|chv_Cyrl|Chuvash
        azb|azb_Arab|South Azerbaijani
        rm|roh_Latn|Romansh
        rn|run_Latn|Kirundi
        hi-Latn|hin_Latn|Hindi (Latin)
        sah|sah_Cyrl|Yakut
        ti|tir_Ethi|Tigrinya
        ast|ast_Latn|Asturian
        oc|oci_Latn|Occitan
        cnh|cnh_Latn|Hakha Chin
        se|sme_Latn|Northern Sami
        nds|nds_Latn|Low German
        os|oss_Cyrl|Ossetian
        ur-Latn|urd_Latn|Urdu (Latin)
        gsw|gsw_Latn|Swiss German
        anp|anp_Deva|Angika
        apc|apc_Arab|Levantine Arabic
        om|gaz_Latn|Oromo
        nap|nap_Latn|Neapolitan
        hsb|hsb_Latn|Upper Sorbian
        hil|hil_Latn|Hiligaynon
        kl|kal_Latn|Greenlandic
    """.trimIndent().lines().map { line ->
        val fields = line.split('|')
        Entry(fields[0], fields[1], fields[2])
    }
    val names: Map<String, String> = entries.associate { it.code to it.name }
    val rightToLeftCodes: Set<String> = entries.filter {
        it.code == "ar" || it.publisherCodes.contains(Regex("_(Arab|Hebr|Thaa)"))
    }.map { it.code }.toSet()
    private val writingScripts: Map<String, Set<Character.UnicodeScript>> = entries.associate { entry ->
        val declared = entry.publisherCodes.split(", ").mapNotNull { identifier ->
            val script = identifier.substringAfter('_', "")
            if (script in setOf("Hans", "Hant", "Hani")) Character.UnicodeScript.HAN
            else runCatching { Character.UnicodeScript.forName(script) }.getOrNull()
        }.toSet()
        val core = when (entry.code) {
            "ru" -> setOf(Character.UnicodeScript.CYRILLIC)
            "ar" -> setOf(Character.UnicodeScript.ARABIC)
            "hi" -> setOf(Character.UnicodeScript.DEVANAGARI)
            "ko" -> setOf(Character.UnicodeScript.HANGUL)
            "ja" -> setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
            "th" -> setOf(Character.UnicodeScript.THAI)
            else -> emptySet()
        }
        entry.code to declared + core
    }
    /** Script can disqualify a source, never distinguish languages sharing that script. */
    fun matchesScript(code: String, script: Character.UnicodeScript): Boolean =
        script != Character.UnicodeScript.LATIN && script in writingScripts[code].orEmpty()
    private val aliases = entries.flatMap { entry ->
        (entry.publisherCodes.split(", ") + entry.code).map {
            it.replace('_', '-').lowercase(Locale.ROOT) to entry.code
        }
    }.toMap() + mapOf("sr-cyrl" to "sr", "uz-latn" to "uz", "ur-arab" to "ur")
    /** Preserve script distinctions that the older base-language normalizer discarded. */
    fun knownCode(value: String): String? = aliases[value.trim().replace('_', '-').lowercase(Locale.ROOT)]
    // Exact names used by the official client; other entries use the report's
    // published English language/variety name in the same natural-language prompt.
    private val promptNames = mapOf(
        "en" to "英语",
        "zh" to "中文",
        "de" to "德语",
        "fr" to "法语",
        "es" to "西班牙语",
        "ja" to "日语",
        "ko" to "韩语",
        "pt" to "葡萄牙语",
        "ru" to "俄语",
        "ar" to "阿拉伯语",
        "it" to "意大利语",
        "nl" to "荷兰语",
        "pl" to "波兰语",
        "ro" to "罗马尼亚语",
        "sv" to "瑞典语",
        "tr" to "土耳其语",
        "hi" to "印地语",
        "vi" to "越南语",
        "th" to "泰语",
        "id" to "印尼语",
        "ms" to "马来语",
        "fil" to "菲律宾语",
        "uk" to "乌克兰语",
        "fa" to "波斯语",
        "cs" to "捷克语",
        "el" to "希腊语",
        "da" to "丹麦语",
        "hu" to "匈牙利语",
        "fi" to "芬兰语",
        "nb" to "书面挪威语",
        "sk" to "斯洛伐克语",
        "bg" to "保加利亚语"
    )
    fun promptName(code: String): String = promptNames[code] ?: checkNotNull(names[code])
}
