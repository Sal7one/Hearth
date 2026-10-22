package com.sal7one.hearth.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Pinned contract for the desktop Moonshine packages.
 *
 * The ten variants are the Moonshine archives in the pinned sherpa-onnx asr-models
 * release (2026-02-27) that the Android catalog already lists. Pins live here, not in
 * [com.sal7one.hearth.core.EngineSelection] options: saved selections pass through the
 * setup allowlist, and a model must be verified even when it is opened from a directory
 * that did not come from the catalog downloader. `scripts/desktop/generate-catalog.py`
 * reads this table, so every shipped card's component pins are these pins.
 */
object MoonshineSpeechModel {
    const val ENGINE_ID = "moonshine-local"
    const val LIBRARY = "hearth_desktop_qwen3_asr"
    const val BACKEND_ID = "moonshine"
    const val ENTRY_SYMBOL = "hearth_moonshine_backend_v1"

    data class PinnedFile(val path: String, val bytes: Long, val sha256: String)

    data class Variant(
        val id: String,
        val label: String,
        val language: String,
        val license: String,
        val archiveRoot: String,
        val files: List<PinnedFile>,
    ) {
        val archiveName: String get() = "$archiveRoot.tar.bz2"
        val expandedBytes: Long get() = files.sumOf { it.bytes }
    }

    val variants: List<Variant> = listOf(
        Variant(
            "moonshine-tiny-en-v2",
            "Moonshine tiny.en · q8",
            "en",
            "MIT",
            "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 13_281_600L, "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"),
                PinnedFile("decoder_model_merged.ort", 30_412_256L, "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-en-v2",
            "Moonshine base.en · q8",
            "en",
            "MIT",
            "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_816L, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
                PinnedFile("decoder_model_merged.ort", 109_424_400L, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-ar-v2",
            "Moonshine base.ar · q8",
            "ar",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-ar-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_824L, "68e50ebe0317ce909f098044a5dda2a76e6b86dc882829a317771fbafc5826ae"),
                PinnedFile("decoder_model_merged.ort", 109_424_552L, "8f272cb50818e28ad86bbffc21e1450a4d57155e95f099ca6a236f38f4d9eafb"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-es-v2",
            "Moonshine base.es · q8",
            "es",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-es-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 532_090L, "c6e35883ba038f70ceea8b4a21cf79742adbda7e6730cbff27460857e0667a02"),
                PinnedFile("encoder_model.ort", 20_964_320L, "331aafa2fc7f7e55ba28376eef08eaf919ae105bb719cd64ba6875505dca72b3"),
                PinnedFile("decoder_model_merged.ort", 43_612_200L, "8e6513ad66a3a71ca86824746a09051eeb60468940eaf8201ce758e8919e2b5d"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-tiny-ja-v2",
            "Moonshine tiny.ja · q8",
            "ja",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 13_238_184L, "86ece73812604b9b5f1274b4d1e6eec0d783b96088ff46d49e30a53f881cad73"),
                PinnedFile("decoder_model_merged.ort", 58_327_272L, "9fcd9b71323a496b307e20dd305c4e9a1b533c7bedd6e4f660e974967dd60bb6"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-tiny-ko-v2",
            "Moonshine tiny.ko · q8",
            "ko",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 13_238_176L, "947260d46252f48eada86a34986b3f70c01d68a343959949a77375b94debd055"),
                PinnedFile("decoder_model_merged.ort", 58_327_336L, "95aa9f2e764b80625d2889d6ec9f05c965808e540ac50c16abd10c7ea33fe44b"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-ja-v2",
            "Moonshine base.ja · q8",
            "ja",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_816L, "3230cafb84d5f08800e60de6f932aad7c69c649a37fa6997fe04fe25f808b56b"),
                PinnedFile("decoder_model_merged.ort", 109_424_424L, "35a522052d2d8695d0dd2870666f088d633111e08cfbed0418f61c4122b4ba25"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-uk-v2",
            "Moonshine base.uk · q8",
            "uk",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-uk-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_816L, "ea37ff7a2c308b566def1fd6e6860e23db4968590f192d5cc0fbc494666e30f9"),
                PinnedFile("decoder_model_merged.ort", 109_424_424L, "2c0c1ebc20ba75a21ff5315fd039e53d2fc9ba77a9ca9ad3bc4c7c1c1de93cbc"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-vi-v2",
            "Moonshine base.vi · q8",
            "vi",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_816L, "5a63cf0e248ef463776fbad50784813b4185eb73136f1fb062c5722e667130dc"),
                PinnedFile("decoder_model_merged.ort", 109_424_520L, "0a4f007e9d585348d94124d8de47f7aefbc3e2d3fa44152646af7b94c639770d"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
        Variant(
            "moonshine-base-zh-v2",
            "Moonshine base.zh · q8",
            "zh",
            "Moonshine Community License",
            "sherpa-onnx-moonshine-base-zh-quantized-2026-02-27",
            listOf(
                PinnedFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                PinnedFile("encoder_model.ort", 31_326_816L, "c725a24b58595905921ea2a47e2bcf0f18c78f4d171d96136f2dcbc8c77a58a6"),
                PinnedFile("decoder_model_merged.ort", 109_424_520L, "bf79fce626e123739ec37eceb2b2a010a93d720da266dd5d8ef9a47ef9a7dc36"),
                PinnedFile("LICENSE", 13_344L, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
            ),
        ),
    )

    /** Every language one of the pinned variants transcribes; Moonshine has no language hint. */
    val languages: Set<String> = variants.mapTo(mutableSetOf()) { it.language }

    fun variant(id: String): Variant = variants.singleOrNull { it.id == id }
        ?: error("Unknown Moonshine desktop model: $id")

    /**
     * Moonshine does not accept a decoder language hint, so Auto resolves to the
     * selected package's own language and an explicit mismatch is a visible error
     * rather than a mislabelled transcript.
     */
    fun language(modelId: String, requested: String): String {
        val variant = variant(modelId)
        val code = requested.trim().lowercase().replace('_', '-').substringBefore('-')
        if (code == "auto") return variant.language
        require(code == variant.language) { "${variant.label} transcribes ${variant.language} only, not $requested" }
        return code
    }

    internal fun verify(root: Path, modelId: String, keepActive: () -> Unit = {}) =
        verify(root, variant(modelId), keepActive)

    internal fun verify(root: Path, variant: Variant, keepActive: () -> Unit = {}) {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Select the complete ${variant.label} model directory" }
        for (file in variant.files) {
            keepActive()
            val target = root.resolve(file.path).normalize()
            require(target.startsWith(root.normalize())) { "Moonshine model path escapes its directory: ${file.path}" }
            var component = root
            for (part in file.path.split('/')) {
                component = component.resolve(part)
                require(!Files.isSymbolicLink(component)) { "Moonshine model contains a symbolic link: ${file.path}" }
            }
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == file.bytes) {
                "Missing or modified Moonshine model file: ${file.path}"
            }
            keepActive()
            require(sha256(target) == file.sha256) { "Moonshine model checksum mismatch: ${file.path}" }
        }
    }
}
