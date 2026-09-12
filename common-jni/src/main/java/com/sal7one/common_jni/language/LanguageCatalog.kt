package com.sal7one.common_jni.language

import java.text.Normalizer
import java.util.Locale

/** Presentation metadata only. Engine/model capabilities decide which codes are selectable. */
data class LanguageOption(val code: String, val nativeName: String, val englishName: String, val flag: String) {
    val label: String get() = if (nativeName == englishName) nativeName else "$nativeName · $englishName"
    val rtl: Boolean get() = code in setOf("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi")
}

object LanguageCatalog {
    // Matches the language IDs in the bundled whisper.cpp g_lang table.
    val whisperCodes: Set<String> = "en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su yue".split(' ').toSet()
    private val regions = "en:GB ar:SA zh:CN yue:HK ru:RU fr:FR de:DE es:ES pt:PT it:IT ja:JP ko:KR hi:IN tr:TR uk:UA nl:NL sv:SE cs:CZ nb:NO no:NO da:DK bg:BG fi:FI hr:HR sk:SK hu:HU ro:RO et:EE th:TH vi:VN ms:MY id:ID fil:PH tl:PH fa:IR el:GR mk:MK km:KH my:MM gu:IN ur:PK te:IN mr:IN he:IL bn:BD ta:IN kk:KZ mn:MN ug:CN pl:PL af:ZA hy:AM as:IN be:BY bs:BA ca:ES ka:GE kn:IN lv:LV lt:LT ne:NP ps:AF pa:IN sr:RS sl:SI az:AZ is:IS sw:KE sq:AL ml:IN si:LK uz:UZ yo:NG am:ET lo:LA ha:NG so:SO mt:MT mg:MG sn:ZW tg:TJ tk:TM tt:RU su:ID jv:ID mi:NZ"
        .split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }
    private val nativeOverrides = mapOf("zh" to "中文", "yue" to "粵語", "ar" to "العربية", "fil" to "Filipino", "tl" to "Tagalog", "bo" to "བོད་སྐད་", "auto" to "Auto-detect")
    fun option(code: String): LanguageOption {
        if (code == "auto") return LanguageOption(code, "Auto-detect", "Auto-detect", "🌐")
        if (code == "model") return LanguageOption(code, "Model language", "Model language", "🌐")
        val locale = Locale.forLanguageTag(code)
        val english = when(code) { "yue" -> "Cantonese"; "fil" -> "Filipino"; else -> locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { code } }
        val native = nativeOverrides[code] ?: locale.getDisplayLanguage(locale).ifBlank { english }
        val flag = regions[code]?.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }?.joinToString("") ?: "🌐"
        return LanguageOption(code, native, english, flag)
    }
    private fun searchable(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    fun choices(codes: Set<String>, query: String = ""): List<LanguageOption> {
        val needle = searchable(query.trim())
        return codes.map(::option).filter { needle.isEmpty() || searchable("${it.code} ${it.nativeName} ${it.englishName}").contains(needle) }
            .sortedWith(compareBy<LanguageOption> { it.code != "auto" && it.code != "model" }.thenBy { it.englishName })
    }
}
