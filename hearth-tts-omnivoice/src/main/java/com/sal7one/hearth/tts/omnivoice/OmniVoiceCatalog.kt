package com.sal7one.hearth.tts.omnivoice

data class OmniVoiceAsset(val filename: String, val bytes: Long, val sha256: String)

/** Code and model terms are separate: OmniVoice pretrained weights are noncommercial. */
object OmniVoiceCatalog {
    const val revision = "017094167b5c9ed565a5076ac9b3b93c5ecf5c73"
    const val source = "https://huggingface.co/Serveurperso/OmniVoice-GGUF"
    const val project = "https://github.com/ServeurpersoCom/omnivoice.cpp"
    const val modelCard = "https://huggingface.co/k2-fsa/OmniVoice"
    const val license = "CC-BY-NC-4.0; codec terms separately applicable"
    val languages = setOf("ar", "en")
    /** OmniVoice's trained language table names Standard Arabic `arb`, not generic `ar`. */
    fun nativeLanguage(language: String): String = if (java.util.Locale.forLanguageTag(language).language == "ar") "arb" else java.util.Locale.forLanguageTag(language).language
    // Design is a requested attribute, never a verified fixed speaker or verified gender.
    val designs = listOf("auto", "design-female", "design-male")
    val assets = listOf(
        OmniVoiceAsset("omnivoice-base-Q8_0.gguf", 656395008, "2882d887921798aea13d45236556bdf8012842ab6f8cd2690943eead6289f298"),
        OmniVoiceAsset("omnivoice-tokenizer-Q8_0.gguf", 288889600, "75204fa566a8e30984e7a1066da6557184c9fd099c8f1bc0cb5b9415edfec255"),
    )
}
