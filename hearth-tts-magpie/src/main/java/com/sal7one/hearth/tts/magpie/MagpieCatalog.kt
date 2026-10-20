package com.sal7one.hearth.tts.magpie

/** Source pins from NVIDIA NeMo-Speech.cpp caa3f47c7c75fa2e9e6340272ebad57c1647dd44. */
data class MagpieAsset(val filename: String, val bytes: Long, val sha256: String)
object MagpieCatalog {
    const val revision = "446bff55e1fe3d3aa29c771574e7d2b0fa322990"
    const val source = "https://huggingface.co/nvidia/magpie_tts_multilingual_357m"
    const val generator = "magpie_tts_multilingual_357m.v2607.f16.gguf"
    const val codec = "nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf"
    val languages = "ar en fr de hi it ko pt es vi".split(' ').toSet()
    // Chinese and Japanese need optional native frontends excluded from this build.
    fun nativeLanguage(language: String): String = when(language.lowercase(java.util.Locale.ROOT)) {
        "ar-sa" -> "ar-SA"
        "ar-ae" -> "ar-AE"
        "ar", "ar-msa" -> "ar-MSA"
        "pt", "pt-br" -> "pt-BR"
        else -> language
    }
    val speakers = listOf("Aria", "Jason", "John", "Leo", "Sofia")
    val weights = listOf(
        MagpieAsset(generator,568663392,"fb8e9297138dcc7b5fc51332f361e3cddfd433d4e0bc1d87e16e6aed21f618fc"),
        MagpieAsset(codec,78823104,"cc86d36d821a27cdc1d4ef600a3e2b0dabe76e88fcc2a8652d9543134c07ef2d"),
    )
    val tokenizer = listOf(
        MagpieAsset("05adc40366e149b69319acd4b28a4919_pt_br_prondict-v1.0.dict",3580284,"6492abc404db16bbad83dd9d7f9a60eb617699f0a3ff54b3be6184e14507b745"),
        MagpieAsset("339da71c54b046f98cbcf38ef6d4ff67_hindi_phoneme_merged_phoneme_dict.dict",7319864,"3b50cc2c21cd8d34983d8492c91308e9db4a58c1194d6506fdbb2b97aead75fc"),
        MagpieAsset("41913ebaa70342058574da74293b7630_magpie_tts_multilingual_357m.nemo.speakers.json",68,"36cdcf01ebc0afb506660ac71f2d0a211236374ad5c872d7d8d985a3f9f6ccaf"),
        MagpieAsset("61d57a4ccb064d1e8e3d9f871c571932_heteronyms-052722",1606,"b701909aedf753172eff223950f8859cd4b9b4c80199cf0a6e9ac4a307c8f8ec"),
        MagpieAsset("74b3e832fe914a569fcd42b51d06b2f1_ipa_dict_nv23.05.txt",5419,"252e8eaf60dfd891520759913dc8534393eebfa8d57192365f76f9229f294e9e"),
        MagpieAsset("7dbc31751f224f2486090d59dc95b9f7_es_ES_nv230301.dict",2230910,"94c9a25bb359f733cd863c887d0112e7ae19a744b0333ef64b6a88e576428669"),
        MagpieAsset("c5e4ec2af5a14ce294f4b9edcc936535_de_nv230119.heteronym",44566,"771cc585a574fd35bd14f4ce6108edf1e0e512a8dbc810db7de4c1165ba9d0ef"),
        MagpieAsset("cf01ab5c48c84f3282ef7888263361e5_de_nv230119.dict",4313907,"5c7bbf3346ebd6dc57769b5cc805124215cb1bcc3b8174c4254e9e928c5d6094"),
        MagpieAsset("dc7d60d6b15a4651b21c9ca2932b62c6_ipa_cmudict-0.7b_nv23.01.txt",3093097,"7ea5c4c2c59cc780748b24d4ab60bd689779c4ac4ea7e27c0a8c3e20f300c0fa"),
        MagpieAsset("model_config.yaml",8358,"9577bfa4769981849feccd6a54d38425d81fff41ac16c282f60be97faf46ebe4"),
    )
    // Exact authenticated append-only .nemo sequence; superseded entries are discarded, never trusted as installed files.
    val archiveMembers = listOf(
        MagpieAsset("05adc40366e149b69319acd4b28a4919_pt_br_prondict-v1.0.dict",3580284,"6492abc404db16bbad83dd9d7f9a60eb617699f0a3ff54b3be6184e14507b745"),
        MagpieAsset("339da71c54b046f98cbcf38ef6d4ff67_hindi_phoneme_merged_phoneme_dict.dict",7320312,"978a7aa5a0e3334b13c19015cc61f3ff1b96ce6ac7a31c482e5c22a61f19586c"),
        MagpieAsset("41913ebaa70342058574da74293b7630_magpie_tts_multilingual_357m.nemo.speakers.json",68,"36cdcf01ebc0afb506660ac71f2d0a211236374ad5c872d7d8d985a3f9f6ccaf"),
        MagpieAsset("61d57a4ccb064d1e8e3d9f871c571932_heteronyms-052722",1606,"b701909aedf753172eff223950f8859cd4b9b4c80199cf0a6e9ac4a307c8f8ec"),
        MagpieAsset("74b3e832fe914a569fcd42b51d06b2f1_ipa_dict_nv23.05.txt",5419,"252e8eaf60dfd891520759913dc8534393eebfa8d57192365f76f9229f294e9e"),
        MagpieAsset("7dbc31751f224f2486090d59dc95b9f7_es_ES_nv230301.dict",2230910,"94c9a25bb359f733cd863c887d0112e7ae19a744b0333ef64b6a88e576428669"),
        MagpieAsset("c5e4ec2af5a14ce294f4b9edcc936535_de_nv230119.heteronym",44566,"771cc585a574fd35bd14f4ce6108edf1e0e512a8dbc810db7de4c1165ba9d0ef"),
        MagpieAsset("cf01ab5c48c84f3282ef7888263361e5_de_nv230119.dict",4313907,"5c7bbf3346ebd6dc57769b5cc805124215cb1bcc3b8174c4254e9e928c5d6094"),
        MagpieAsset("dc7d60d6b15a4651b21c9ca2932b62c6_ipa_cmudict-0.7b_nv23.01.txt",3093097,"dd0927fffc89e8539ea0a26ccbc164a908f4b3de9613d924c90afa5300e00f72"),
        MagpieAsset("model_config.yaml",7496,"fda01948a40a04316b26553b53546cbd2103952b8d0e2813176ea33852bb96a2"),
        MagpieAsset("model_config.yaml",7910,"7f21112e0f220f349ecd82f70d2f2ee619e8f68a1f60f3c2b5091fa41adb64b4"),
        MagpieAsset("model_config.yaml",7938,"6b0dd49c3bdac3e5770561f791bccbe5cc70599d64586c953c1aae7ed2a2289f"),
        MagpieAsset("model_config.yaml",7938,"a942d5c9867b02f05d480463fdf6beadb55bb791f6da7e59a8da9a9892eec107"),
        MagpieAsset("dc7d60d6b15a4651b21c9ca2932b62c6_ipa_cmudict-0.7b_nv23.01.txt",3093097,"7ea5c4c2c59cc780748b24d4ab60bd689779c4ac4ea7e27c0a8c3e20f300c0fa"),
        MagpieAsset("339da71c54b046f98cbcf38ef6d4ff67_hindi_phoneme_merged_phoneme_dict.dict",7319864,"3b50cc2c21cd8d34983d8492c91308e9db4a58c1194d6506fdbb2b97aead75fc"),
        MagpieAsset("model_config.yaml",8358,"9577bfa4769981849feccd6a54d38425d81fff41ac16c282f60be97faf46ebe4"),
    )
    const val tokenizerSegmentsBytes = 31076864L
    const val tokenizerSegmentsSha256 = "169144a64f6d775178696914e5aad8282db44820e73341d5f9a4e7865fac1f26"
    val ranges = listOf(0L..20619263L,1459750400L..1470207999L)
    val installedBytes get() = weights.sumOf { it.bytes } + tokenizer.sumOf { it.bytes }
    val downloadBytes get() = weights.sumOf { it.bytes } + tokenizerSegmentsBytes
}
