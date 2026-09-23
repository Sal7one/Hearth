package com.sal7one.transiber.models

import com.sal7one.common_jni.language.LanguageCatalog

/** SHA-256 values are publisher LFS object IDs at this immutable repository revision. */
internal object WhisperDownloads {
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val REPOSITORY = "https://huggingface.co/ggerganov/whisper.cpp"
    val all: List<SpeechArtifact> = listOf(
        entry("tiny", "Q5_1", "ggml-tiny-q5_1.bin", 32152673L, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", "2023-09-06"),
        entry("tiny", "Q8_0", "ggml-tiny-q8_0.bin", 43537433L, "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca", "2024-10-29"),
        entry("tiny", "F16", "ggml-tiny.bin", 77691713L, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", "2023-03-22"),
        entry("tiny.en", "Q5_1", "ggml-tiny.en-q5_1.bin", 32166155L, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", "2023-09-06"),
        entry("tiny.en", "Q8_0", "ggml-tiny.en-q8_0.bin", 43550795L, "5bc2b3860aa151a4c6e7bb095e1fcce7cf12c7b020ca08dcec0c6d018bb7dd94", "2023-12-10"),
        entry("tiny.en", "F16", "ggml-tiny.en.bin", 77704715L, "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", "2023-03-22"),
        entry("base", "Q5_1", "ggml-base-q5_1.bin", 59707625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", "2023-09-06"),
        entry("base", "Q8_0", "ggml-base-q8_0.bin", 81768585L, "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9", "2024-10-29"),
        entry("base", "F16", "ggml-base.bin", 147951465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "2023-03-22"),
        entry("base.en", "Q5_1", "ggml-base.en-q5_1.bin", 59721011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", "2023-09-06"),
        entry("base.en", "Q8_0", "ggml-base.en-q8_0.bin", 81781811L, "a4d4a0768075e13cfd7e19df3ae2dbc4a68d37d36a7dad45e8410c9a34f8c87e", "2024-10-29"),
        entry("base.en", "F16", "ggml-base.en.bin", 147964211L, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", "2023-03-22"),
        entry("small", "Q5_1", "ggml-small-q5_1.bin", 190085487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", "2023-09-06"),
        entry("small", "Q8_0", "ggml-small-q8_0.bin", 264464607L, "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", "2024-10-29"),
        entry("small", "F16", "ggml-small.bin", 487601967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", "2023-03-22"),
        entry("small.en", "Q5_1", "ggml-small.en-q5_1.bin", 190098681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", "2023-09-06"),
        entry("small.en", "Q8_0", "ggml-small.en-q8_0.bin", 264477561L, "67a179f608ea6114bd3fdb9060e762b588a3fb3bd00c4387971be4d177958067", "2024-10-29"),
        entry("small.en", "F16", "ggml-small.en.bin", 487614201L, "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d", "2023-03-22"),
        entry("medium", "Q5_0", "ggml-medium-q5_0.bin", 539212467L, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", "2023-09-06"),
        entry("medium", "Q8_0", "ggml-medium-q8_0.bin", 823369779L, "42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502", "2024-10-29"),
        entry("medium", "F16", "ggml-medium.bin", 1533763059L, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208", "2023-03-22"),
        entry("medium.en", "Q5_0", "ggml-medium.en-q5_0.bin", 539225533L, "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0", "2023-09-06"),
        entry("medium.en", "Q8_0", "ggml-medium.en-q8_0.bin", 823382461L, "43fa2cd084de5a04399a896a9a7a786064e221365c01700cea4666005218f11c", "2024-10-29"),
        entry("medium.en", "F16", "ggml-medium.en.bin", 1533774781L, "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356", "2023-03-22"),
        entry("large-v3-turbo", "Q5_0", "ggml-large-v3-turbo-q5_0.bin", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", "2024-10-01"),
        entry("large-v3-turbo", "Q8_0", "ggml-large-v3-turbo-q8_0.bin", 874188075L, "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", "2024-10-29"),
        entry("large-v3-turbo", "F16", "ggml-large-v3-turbo.bin", 1624555275L, "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", "2024-10-01"),
    )
    private fun entry(checkpoint: String, quant: String, file: String, bytes: Long, sha256: String,
                      artifactDate: String): SpeechArtifact {
        val english = checkpoint.endsWith(".en")
        val turbo = checkpoint == "large-v3-turbo"
        val id = "whisper-$checkpoint-${quant.lowercase(java.util.Locale.ROOT)}"
        return SpeechArtifact(id, "whisper", checkpoint, quant,
            "Whisper · $checkpoint · $quant · ${if (english) "English" else "Multilingual"}",
            SpeechArtifactKind.WHISPER, "$REPOSITORY/resolve/$REVISION/$file", bytes, sha256, REVISION,
            if (turbo) "2024-10-01" else "2022-09-21", artifactDate,
            if (english) setOf("en") else if (turbo) LanguageCatalog.whisperCodes else LanguageCatalog.whisperCodes - "yue",
            "MIT", REPOSITORY, installedBytes = bytes, advanced = checkpoint.startsWith("medium") || turbo)
    }
}
