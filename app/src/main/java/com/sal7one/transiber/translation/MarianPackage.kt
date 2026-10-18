package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.transiber.downloads.DownloadAsset
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterInputStream

/** Exact four-file OPUS-MT ONNX packages with publisher pins and atomic installation. */
internal object MarianPackage {
    data class Part(
        val pairId: String, val repository: String, val revision: String,
        val suffix: String, val fileName: String, val path: String,
        val bytes: Long, val sha256: String, val installedBytes: Long,
    ) {
        val id get() = "$pairId-$suffix"
        val url get() = "https://huggingface.co/$repository/resolve/$revision/$path"
        // Preserve the first package's public filenames for existing downloads.
        val downloadName get() = if (pairId == "marian-en-ar") fileName else "$pairId-$fileName"
        fun asset() = DownloadAsset(id, url, bytes, sha256, installedBytes)
    }
    data class Pair(
        val id: String, val source: String, val target: String,
        val repository: String, val revision: String, val treeSha256: String,
        val parts: List<Part>, val originalLicense: String,
    ) {
        val downloadBytes get() = parts.sumOf { it.bytes }
        val modelCard get() = "https://huggingface.co/$repository"
        val filesUrl get() = "$modelCard/tree/$revision"
        val originalModelCard get() = "https://huggingface.co/Helsinki-NLP/opus-mt-$source-$target"
        val licenseUrl get() = "$modelCard/blob/$revision/README.md"
        val license get() = "CC-BY-4.0 (ONNX conversion); $originalLicense (original Helsinki-NLP model)"
    }

    const val license = "CC-BY-4.0 (ONNX conversions and English→German original); Apache-2.0 (other originals)"
    private data class FilePin(val bytes: Long, val sha256: String, val path: String)
    private fun pair(source: String, target: String, revision: String, tree: String,
                     spm: FilePin, tokenizer: FilePin, encoder: FilePin, decoder: FilePin,
                     originalLicense: String = "Apache-2.0"): Pair {
        val id = "marian-$source-$target"
        val repository = "onnx-community/opus-mt-$source-$target"
        val total = spm.bytes + tokenizer.bytes + encoder.bytes + decoder.bytes
        fun part(suffix: String, fileName: String, pin: FilePin) =
            Part(id, repository, revision, suffix, fileName, pin.path, pin.bytes, pin.sha256, total)
        return Pair(id, source, target, repository, revision, tree,
            listOf(part("spm", "source.spm", spm), part("tokenizer", "tokenizer.json", tokenizer),
                part("encoder", "encoder_model_quantized.onnx", encoder),
                part("decoder", "decoder_model_merged_quantized.onnx", decoder)), originalLicense)
    }
    val pairs = listOf(
        pair("en", "ar", "d0118f36228ba00fb7ec2306ab5824fcd81e5c03",
            "95a719c4c037310789b44358a459cd4b10a2cd187944b21101d4531d290e225b",
            FilePin(801074, "81ac8f4d4ee271f4109eac67560a31f9c2d555463ba5eb95c32c6deee8ae819d", "source.spm"),
            FilePin(6748848, "109bb62e1c598287f5960616c869e15be54785a3c2906903dafe044241d50b61", "tokenizer.json"),
            FilePin(51749190, "ebce26ed64f2d6efd09098f5752f283ae7bdf593114d8e2add769f51a395d91b", "onnx/encoder_model_quantized.onnx"),
            FilePin(187651988, "ae11f1426d824134ad3788bc44f09c7c8c42876c386bd7474cd181b262f82c89", "onnx/decoder_model_merged_quantized.onnx")),
        pair("ru", "en", "92ef0d550ca96ebd9cd5d13aab6ad41854d99a3d",
            "d083f647b1fe640dbbbfcfe2372cf34926fd45e7eb6f0da89775ebaa79fa1768",
            FilePin(1080169, "745998e51ba5b058e38b7ac7765c25c43ed5c1c39cc92b27163b9b2e323c9d7c", "source.spm"),
            FilePin(7205388, "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb", "tokenizer.json"),
            FilePin(51603782, "fdd4d1de9cb02feaae8bc892e0e21b1bfd1741fa43a2895d471ee5f53ae260c4", "onnx/encoder_model_quantized.onnx"),
            FilePin(186923812, "0fef11505dc0564d0d6d54e37529d7ba949826fe19fd7193052610989fccbf28", "onnx/decoder_model_merged_quantized.onnx")),
        pair("zh", "en", "8e3032ebeebbacda779fe95efa64c03b962f83f3",
            "2338de6a833df68301890c637145fe7c1b67cbac13ede10f1f2c21477931802c",
            FilePin(804677, "e27a3a1b539f4959ec72ea60e453f49156289f95d4e6000b29332efc45616203", "source.spm"),
            FilePin(6381339, "b306d0301cf280bfd647d7067b5ade2a97b987e6d678df110703c002433643ff", "tokenizer.json"),
            FilePin(52875078, "86b0dc5a1d5d8062583800654864aae1311fce2172bba80910d02020d3693577", "onnx/encoder_model_quantized.onnx"),
            FilePin(193290224, "714881fafd326c8cb56bc6e3e542d1d106a5acf90e6abb71e20423ebf1b47875", "onnx/decoder_model_merged_quantized.onnx")),
        pair("ar", "en", "3302f1bd7f2c38f272c73a2b82345fae3bea35ce",
            "b9f5b17416e1735a66433311ab72b2c52910dbe14a44eb8b6f7ad6ed49416aa3",
            FilePin(917407, "484f7210e5f4466f7e5b99b660717c6ef2a6f90746196daba507795b69dc02a2", "source.spm"),
            FilePin(6734647, "86c78b55d2428e4479e21ce4c27266f7c5e1d52bcabcadff29bfda77f6ad3200", "tokenizer.json"),
            FilePin(51765574, "7b8e80e9293d3debe958f16db24183b1f33ba442aae6fa7c9b0a595d7b6d8f72", "onnx/encoder_model_quantized.onnx"),
            FilePin(187734036, "b7639a1150df9e7a48eb5e04ea8ad4d7100d8e97c72d547d9a175f7d6e7d4aed", "onnx/decoder_model_merged_quantized.onnx")),
        pair("en", "ru", "c6967b328d292f9f3d14bab6e9de40f9bd367114",
            "fc29762221d09e2ee59866c7c3585e7c3b8daf0d229088f3bd0309bfbae0d80d",
            FilePin(802781, "16bebef1389a0b8ab452772c4e35b9e605e5713f8ac7baa71ca701394eaa086d", "source.spm"),
            FilePin(7205388, "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb", "tokenizer.json"),
            FilePin(51603782, "b8b4f72528c0da92e579af8a739f97fa2f792d73527ba2fee786fa7286c4055b", "onnx/encoder_model_quantized.onnx"),
            FilePin(186923812, "5d15c48566e60dcaa5af7422bcceca24ccc8f3eaa8f1b3aedc5d6170e6c232f3", "onnx/decoder_model_merged_quantized.onnx")),
        pair("en", "zh", "4dc37afc6deb160f4c9048260d48019ee497658e",
            "ac77da24d00301dea71b15773b5bffb287a8d8667f53079a20dbc9fdc2d25ce9",
            FilePin(806435, "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8", "source.spm"),
            FilePin(6380952, "d0c7da27056e8f42adce9e76d8e792e5daa64e15f5acd2e7aabf0121877dd4c1", "tokenizer.json"),
            FilePin(52875078, "cb02c542d2ab3010766031f3ce04e9041ffb6bd2df15caa7c41f3aead7cfe6dc", "onnx/encoder_model_quantized.onnx"),
            FilePin(193290224, "c6fe901b5bae6235237f07ac0dfb421bf2a14aaaa02d4024bd1965be5b1d215e", "onnx/decoder_model_merged_quantized.onnx")),
        pair("en", "fr", "060ab253b6aae185f277c1e048fb0cf61a02c6c0",
            "2d44dfb7c043a6a3dc850de148e97d1bbb0929ceb641a61079e4bc035d5d2cfe",
            FilePin(778395, "173e9f493a668fe396d599e28d414a201193094e6ffd7a4678e5aab0f6d3d838", "source.spm"),
            FilePin(5637839, "8391785c1a2139e7af4678571ccd8dc654ecbb72e4be186940f65d7c604f0246", "tokenizer.json"),
            FilePin(50065734, "b6190be92972c9674abb94561dcffc12d89463269748be0db459974037319b79", "onnx/encoder_model_quantized.onnx"),
            FilePin(179221556, "6e95be4cb0e4243202a6ab6ad77db65a419eea13cfda7b05735c3cf1306de2f8", "onnx/decoder_model_merged_quantized.onnx")),
        pair("fr", "en", "f7e3c392bacfe300a66b0ddbd20de1d2dfc3b77e",
            "faa733a4b0496fef782943eccd6c733e0c5f2da17d481ef63a2864a7f57794b0",
            FilePin(802397, "78d0e717c77053f1c4b856d8661d9cb87c64f083a35418c087b9146300e4f585", "source.spm"),
            FilePin(5637839, "8391785c1a2139e7af4678571ccd8dc654ecbb72e4be186940f65d7c604f0246", "tokenizer.json"),
            FilePin(50065734, "df4c59dc69e422f504ccb7bf1895f656f20e16542135c1da1d7092b80b17e095", "onnx/encoder_model_quantized.onnx"),
            FilePin(179221556, "03eed490ede7098a0ca2f6ef578904d2e16fc6893dbe2ac4b787677a905d2046", "onnx/decoder_model_merged_quantized.onnx")),
        pair("en", "de", "b4306231768977e5956c21c26c3ae14d6b2713c4",
            "0794a89400d949ccf6cb57a899e82e6b9668c781d3fe3665a238e91a1ea46a14",
            FilePin(768489, "678f2a1177d8389f67b66299762dcc4fc567e89b07e212ba91b0c56daecf47ce", "source.spm"),
            FilePin(5498450, "8e0fcf45621ea87fa680c7f9969c37a7f819c1f4c7658a2e6e0879b866a14b17", "tokenizer.json"),
            FilePin(49342278, "94ae6a9149aca29ef31a58bb8ccc1c3df3720840caef890ccc6cde73c94cb0f4", "onnx/encoder_model_quantized.onnx"),
            FilePin(175598624, "492004d70327f4552fbddba033f8d8ce946edb87b66cbf8f9a7b41d14fe683cc", "onnx/decoder_model_merged_quantized.onnx"),
            originalLicense = "CC-BY-4.0"),
        pair("de", "en", "85f2f23c5f1863b741db8b9128cfbe7a5cbc0653",
            "b1ccf953a3a5771fbdf697597697a22c1d5d9c1aa7b3f731c298a98b8cc47543",
            FilePin(796845, "bbd1f495eea99c8e21ae086d9146e0fa7b096c3dfdd9ba07ab8b631889df5c9b", "source.spm"),
            FilePin(5498450, "8e0fcf45621ea87fa680c7f9969c37a7f819c1f4c7658a2e6e0879b866a14b17", "tokenizer.json"),
            FilePin(49342278, "3e7b95246cf1885b5c6c123a36818a417c3ef6f500d4c17e030fef427fff7a74", "onnx/encoder_model_quantized.onnx"),
            FilePin(175598624, "d789d6a132d540fa40d6fa5901c3ba1e6209c69bf201ffef54f73833fa9b5a6c", "onnx/decoder_model_merged_quantized.onnx")),
        pair("en", "es", "b5eba94a023a1954c90401b43537f479f962981d",
            "38367778c3b0bd8044ca54625efa602e3458c585ca240580bc0faa5d25c4a978",
            FilePin(801636, "4dd547c24816a335e7b0b2e63376a8f1b3cbfc671eda5ab808dd44fdadaa8791", "source.spm"),
            FilePin(6262682, "285eb29e7155ee48851a77960797813f86a125f70d2c1a124f613f1fbd2b19c3", "tokenizer.json"),
            FilePin(52875078, "13ec84a3afebfe97cae004a5f39881ea38541308514ec26c18a0b807476d6fba", "onnx/encoder_model_quantized.onnx"),
            FilePin(193290224, "832c4e0c1630a401f3115f0fcb08922f473b7f4996a5371d02ff880dc55f9399", "onnx/decoder_model_merged_quantized.onnx")),
        pair("es", "en", "0ca6ea53b39356aece8a15bd92eaf7bb40035ea6",
            "9a0f9cb3fc2b4112e3e54a692fe61fe2089d74aab64acdfcb7f67e0cc9dbb27a",
            FilePin(825924, "e236ee6d866b635c0142114f8647f39831f9d92534aa2aad75c942f6a78ad0e3", "source.spm"),
            FilePin(6262682, "285eb29e7155ee48851a77960797813f86a125f70d2c1a124f613f1fbd2b19c3", "tokenizer.json"),
            FilePin(52875078, "b888df16d3a7708182beb241f1ad50d2b4cf20a7133a2888bb95cb8dcbff9c98", "onnx/encoder_model_quantized.onnx"),
            FilePin(193290224, "5ddb433bf02cb87f48dc4ff2c0cf4c96ed4b4c154ba5089027f90031f3564efc", "onnx/decoder_model_merged_quantized.onnx")),
    )
    fun find(id: String): Pair? = pairs.firstOrNull { it.id == id }
    fun part(id: String): Part? = pairs.asSequence().flatMap { it.parts.asSequence() }.firstOrNull { it.id == id }
    fun pairForPart(id: String): Pair? = pairs.firstOrNull { pair -> pair.parts.any { it.id == id } }
    fun installed(context: Context, pair: Pair): ModelRegistry.RegisteredModel? =
        ModelRegistry.getInstance(context).getModelsForEngine(ModelEngineType.TRANSLATE)
            .firstOrNull { it.digest?.hex == pair.treeSha256 && it.isDirectory }

    fun enqueue(context: Context, downloads: FileDownloads, pair: Pair) {
        installed(context, pair)?.let { model ->
            if (runCatching { ModelRegistry.getInstance(context).getProvider(model.id)?.getModelPath()
                    ?: error("Installed model folder is missing") }.isSuccess) return
        }
        for (part in pair.parts) {
            downloads.enqueue(DownloadSpec.parse(part.url, part.downloadName), true, part.id)
        }
    }

    /** Returns null until all originals verify; never publishes a partial folder. */
    suspend fun install(context: Context, downloads: FileDownloads, pair: Pair): String? {
        val registry = ModelRegistry.getInstance(context)
        installed(context, pair)?.let { existing ->
            if (runCatching { registry.getProvider(existing.id)?.getModelPath() ?: error("Installed model folder is missing") }.isSuccess)
                return existing.id
            // An explicit install/reinstall can replace a damaged private snapshot.
            registry.unregisterModel(existing.id, deleteFiles = true)
        }
        val job = currentCoroutineContext()
        val originals = pair.parts.map { part ->
            downloads.list().firstOrNull { row -> row.modelId == part.id &&
                downloads.record(row.id)?.optBoolean("downloadComplete") == true } ?: return null
        }
        val result = registry.registerPinnedDirectory(
            "opus-mt-${pair.source}-${pair.target}", pair.treeSha256) { sink ->
            pair.parts.zip(originals).forEach { (part, row) ->
                job.ensureActive()
                context.contentResolver.openInputStream(downloads.uri(row.id))?.use { input ->
                    val cancellable = object : FilterInputStream(input) {
                        override fun read(): Int { job.ensureActive(); return super.read() }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            job.ensureActive(); return super.read(bytes, offset, length)
                        }
                    }
                    sink.addFile(part.fileName, cancellable)
                }
                    ?: error("Cannot read downloaded ${part.downloadName}")
            }
            job.ensureActive()
        }
        check(result.engineType == ModelEngineType.TRANSLATE) { "Publisher package is not a translation model" }
        return result.id
    }

    fun modelDirectory(context: Context, pair: Pair): File {
        val model = installed(context, pair) ?: error("Download and install Marian ${pair.source} → ${pair.target} in Models first")
        val path = ModelRegistry.getInstance(context).getProvider(model.id)?.getModelPath()
            ?: error("Marian model provider is missing")
        return File(path)
    }
}
