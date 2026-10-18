package com.sal7one.transiber.models

/** Archive pins and expanded-tree pins are independent; versioned publisher URLs are not immutable. */
internal data class VoskDownload(val artifact: SpeechArtifact, val expandedBytes: Long, val treeSha256: String) {
    init {
        require(artifact.kind == SpeechArtifactKind.VOSK && artifact.languages.size == 1)
        require(expandedBytes > 0 && treeSha256.matches(Regex("[a-f0-9]{64}")))
    }
    val language get() = artifact.languages.single()
}

/** Official wideband Vosk models; all seven archived mfcc.conf files specify 16 kHz. */
internal object VoskDownloads {
    private const val PUBLISHER = "https://alphacephei.com/vosk/models"
    val packages: List<VoskDownload> = listOf(
        entry("small-en-us-0.15", "en", "US English · Small 0.15", 41205931L,
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", 70898967L,
            "1855931c4d84fa0f731a5a7906c1356837335d49ee7f2ca6a6a32389bac7f8e8"),
        entry("small-es-0.42", "es", "Spanish · Small 0.42", 39817833L,
            "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f", 60286598L,
            "aaf9b97e2b0efadfdbaabb97900e620884908a554fd1f5072339d3f1458ad129"),
        entry("small-fr-0.22", "fr", "French · Small 0.22", 42233323L,
            "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad", 68483807L,
            "42427727ea015131d1f3f54c21bfeb772945b8682a87b6165dc61d9aa3d4d3c4"),
        entry("small-de-0.15", "de", "German · Small 0.15", 46499967L,
            "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43", 95621761L,
            "b8a470cd032c88ea1bad4b173071a30c014ffa345f45a41499747fdac69a8743"),
        entry("small-ru-0.22", "ru", "Russian · Small 0.22", 46236750L,
            "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4", 91289240L,
            "4f1bd9e45f0a1c1fd669b6cb75ae1bcded09ed9e22de60247bb57a40814f0f07"),
        entry("small-cn-0.22", "zh", "Chinese · Small 0.22", 43898754L,
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba", 68292271L,
            "ebbc20212be98c8cf0c5076915ebf2854106a7bf5ca376b11110f28b042e4555"),
        entry("ar-mgb2-0.4", "ar", "Arabic · MGB-2 0.4 · larger model", 333241610L,
            "357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a", 698195675L,
            "5a19af86876e218f81e26d6b79ca7317e2644821e8a235e7e25176457bd88afa"),
    )
    val all: List<SpeechArtifact> = packages.map { it.artifact }
    fun find(id: String): VoskDownload? = packages.firstOrNull { it.artifact.id == id }
    fun findByTreeSha256(digest: String?): VoskDownload? = packages.firstOrNull { it.treeSha256 == digest }

    private fun entry(checkpoint: String, language: String, label: String, bytes: Long, sha256: String,
                      expandedBytes: Long, treeSha256: String): VoskDownload {
        val root = "vosk-model-$checkpoint"
        val artifact = SpeechArtifact("vosk-$checkpoint", "vosk", checkpoint, "Kaldi", "Vosk · $label",
            SpeechArtifactKind.VOSK, "$PUBLISHER/$root.zip", bytes, sha256, "archive-sha256:$sha256",
            null, null, setOf(language), "Apache-2.0", PUBLISHER,
            // Installation also retains a private verified ZIP until extraction completes.
            installedBytes = Math.addExact(bytes, expandedBytes), advanced = language == "ar", archiveRoot = root)
        return VoskDownload(artifact, expandedBytes, treeSha256)
    }
}
