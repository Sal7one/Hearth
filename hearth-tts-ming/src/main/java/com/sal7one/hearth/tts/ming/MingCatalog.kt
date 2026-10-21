package com.sal7one.hearth.tts.ming

import java.util.Locale

/** One pinned file from the 850 MB `fusorlabs/ming-3.0-tts-0.5b-web` runtime bundle. */
data class MingAsset(val filename: String, val bytes: Long, val sha256: String, val url: String)

/**
 * The Android side of the experimental Ming-3.0 TTS local runtime.
 *
 * This is the same pinned bundle and 44.1 kHz WAV contract as the desktop `ming-local` engine,
 * but Android stages an arm64 JNI library and imports the bundle through the app's model library.
 * The runtime hardcodes `language: None`; only English and Chinese are advertised, never the
 * publisher's other five languages.
 */
object MingCatalog {
    const val FAMILY = "ming-local"
    const val ENGINE_ID = "ming-local"
    const val DISPLAY_NAME = "Ming-3.0 TTS 0.5B"
    const val REVISION = "62588575d91dceb33822fe1e3925e748393c6b3e"
    const val SOURCE = "https://huggingface.co/fusorlabs/ming-3.0-tts-0.5b-web"
    const val MODEL_CARD = "https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B"
    const val LICENSE = "MIT"
    const val SAMPLE_RATE = 44_100
    const val MAXIMUM_TEXT_CHARACTERS = 300
    const val MAX_THREADS = 4
    const val SEED = 1895L
    const val PRESET = 0

    /** The runtime has no language switch. These are the only languages its shipped voices use. */
    val languages: Set<String> = linkedSetOf("en", "zh")

    /** The five published English presets plus the two Chinese presets shipped by this runtime. */
    val voices: List<String> = listOf(
        "US_James", "US_Emma", "US_Stella", "UK_George", "UK_Charlotte", "灵小柔", "灵楷",
    )

    private val base = "$SOURCE/resolve/$REVISION"

    val assets: List<MingAsset> = buildList {
        add(MingAsset("manifest.json", 88_437L, "a50db3af1bfa6dfcbba12bfaca42c7d7d85fc440e76ee63b1a2a99f39cf36f05", "$base/manifest.json"))
        add(MingAsset("tokenizer.json", 11_431_389L, "87d54ca3b44ea2f822ba39a0a45cb1b61644bd6606d9d3536dea75367bffb06d", "$base/tokenizer.json"))
        addAll(
            listOf(
                153_151_488L to "018d43eaf832edfa69ba0096a60473850ac2d53019dd7e7102e10e9d69a8a58e",
                47_809_536L to "79e273bc0c6b078c4571a86131eb9ed382daf24a5b5f58edf0a3c90b00da5fc6",
                49_374_720L to "221d0161d1007ae8e8c8de3d02f5c87002a5dcedbc38fe46e392a1467c45b5c0",
                49_374_720L to "317cb4dc946ed5ed4fca4a059099c2ced5f19c8a30d183a6dd6df4b27cf4b281",
                50_028_032L to "bb3b243dc4648a01db1aaf6bd08c680a8ef49880076b51681d90339f79e2c971",
                49_883_648L to "138c06bfbac9059121dfbbf4f25993c0fb6c589f670bd8299e2d74033ef0c640",
                49_374_720L to "e460bd70b20ec14a6d603aaf075a2e275ad0aea1ee7c89d6299220fd0f6c8b33",
                50_028_032L to "ddf4c79abf8aa8a7df88f23820d591718aa1344cc4c0184b8f13b65fff7c4660",
                49_880_064L to "2ae3893f08712067981d29dfff3f754c7c0944522bd8f0cb04d251c5e592e1af",
                49_374_720L to "429b0551b819e339e12f7f8134e64c2cd79c137b01df9ed6bb4facffc25188ca",
                47_382_272L to "091292f51b1d33b2f34bfe886bae8b3076dd855ba4122604fdb04df84c49e334",
                47_366_144L to "543b01946f9455d87569c27bf3167c1c9271499fd2dfb732b5724a6cb15a9c90",
                48_627_200L to "316d6fce01f71a016a60013f335aab496e77aae13324ee32737acb7f0abf819d",
                47_321_088L to "3f55b8b32511799a0863ee567d033874a81ca4e627e0ef7e33dbe02cbc55722f",
                49_619_520L to "ee8cd2ff0385c4b99b6dfe4556be7326e53b30cf93258d52028c5af0f3550a1c",
            ).mapIndexed { index, (bytes, sha256) ->
                MingAsset("part-%03d.bin".format(index), bytes, sha256, "$base/parts/%03d.bin".format(index))
            },
        )
    }

    /** Flat install bytes; the runtime reads a linked `parts/` copy of the same 15 part files. */
    val totalBytes: Long = assets.sumOf { it.bytes }

    val filesUrl: String get() = "$SOURCE/tree/$REVISION"
    val licenseUrl: String get() = MODEL_CARD

    /** `zh-Hans`, `en-US` and `zh` all resolve to the two advertised codes; anything else is rejected. */
    fun language(tag: String): String? =
        Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT).takeIf { it in languages }

    /** Keep Android's big.LITTLE core count from over-subscribing the ~1 GB runtime. */
    fun defaultThreads(processors: Int = Runtime.getRuntime().availableProcessors()): Int =
        processors.coerceIn(1, MAX_THREADS)

    /**
     * Maps a bundle ZIP entry to the flat catalog filename the app installs.
     *
     * The Hugging Face repository stores parts under `parts/000.bin`, while the app installs the
     * flat `part-000.bin` names the runtime's parts/ layout links to.
     */
    fun flatFilename(entryPath: String): String? {
        val normalized = entryPath.replace('\\', '/').trimStart('/')
        val name = normalized.substringAfterLast('/')
        if (name == "manifest.json" || name == "tokenizer.json") return name
        if (Regex("^part-\\d{3}\\.bin$").matches(name)) return name
        if (!Regex("^\\d{3}\\.bin$").matches(name)) return null
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.substringAfterLast('/') != "parts") return null
        return "part-$name"
    }
}
