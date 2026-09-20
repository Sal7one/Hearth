package com.sal7one.transiber.byok

import java.util.Locale

/**
 * OpenAI's translation schema accepts a language string, not a published enum.
 * ISO names are picker suggestions, NOT a claim of model coverage. Session errors remain verbatim.
 * https://developers.openai.com/api/reference/resources/realtime/translation-client-events
 * No discovery endpoint is documented; do not scrape docs or infer languages from /models.
 */
object CloudSpeechLanguages {
    val openAiTranslationSuggestions: Set<String> by lazy {
        Locale.getISOLanguages().map { Locale.forLanguageTag(it).language }.filter { it.length == 2 }.toSet()
    }
    fun isExplicitLanguageCode(code: String): Boolean =
        code.matches(Regex("[a-z]{2,3}")) && code !in setOf("und", "mul", "zxx")

    // Conservative common-language hint set for the explicitly identified cloud models above.
    // This is not a claim of exhaustive provider coverage; unknown models never inherit it.
    val cloudTranscriptionCodes = "af ar hy az be bs bg ca zh hr cs da nl en et fi fr gl de el he hi hu is id it ja kn kk ko lv lt mk ms mr mi ne no fa pl pt ro ru sr sk sl es sw sv tl ta th tr uk ur vi cy".split(' ').toSet()

    // Nova-3 base codes, publisher documentation checked 2026-09-12. Locale variants remain provider-specific.
    val deepgramCodes = "af ar hy as be bn bs bg ca zh hr cs da nl en et fi fr ka de el gu he hi hu id it ja kn kk ko lv lt mk ms mr mn ne no ps fa pl pt pa ro ru sr sk sl es sv tl ta te th tr uk ur vi".split(' ').toSet()

    val sonioxCodes = "af sq ar az eu be bn bs bg ca zh hr cs da nl en et fi fr gl de el gu he hi hu id it ja kn kk ko lv lt mk ms ml mr no fa pl pt pa ro ru sr sk sl es sw sv tl ta te th tr uk ur vi cy".split(' ').toSet()
    // Full publisher language snapshot for Scribe v2, replacing the old 23-language UI shortlist.
    // Realtime uses ISO 639-1 or 639-3. Metadata is model-specific, not a global language allowlist.
    // https://elevenlabs.io/docs/overview/capabilities/speech-to-text (2026-09-20)
    // https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime
    val scribeCodes = "af am ar hy as ast az be bn bs bg my yue ca ceb ny hr cs da nl en et fil fi fr ff gl lg ka de el gu ha he hi hu is ig id ga it ja jv kea kn kk km ko ku ky lo lv ln lt luo lb mk ms ml mt zh mi mr mn ne nso no oc or ps fa pl pt pa ro ru sr sn sd si sk sl so es sw sv ta tg te th tr uk umb ur uz vi cy wo xh zu".split(' ').toSet()

}
