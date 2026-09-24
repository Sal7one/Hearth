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
        val parts: List<Part>,
    ) {
        val downloadBytes get() = parts.sumOf { it.bytes }
        val modelCard get() = "https://huggingface.co/$repository"
    }

    const val license = "CC-BY-4.0 (ONNX conversion); Apache-2.0 (original Helsinki-NLP model)"
    private data class FilePin(val bytes: Long, val sha256: String, val path: String)
    private fun pair(source: String, target: String, revision: String, tree: String,
                     spm: FilePin, tokenizer: FilePin, encoder: FilePin, decoder: FilePin): Pair {
        val id = "marian-$source-$target"
        val repository = "onnx-community/opus-mt-$source-$target"
        val total = spm.bytes + tokenizer.bytes + encoder.bytes + decoder.bytes
        fun part(suffix: String, fileName: String, pin: FilePin) =
            Part(id, repository, revision, suffix, fileName, pin.path, pin.bytes, pin.sha256, total)
        return Pair(id, source, target, repository, revision, tree,
            listOf(part("spm", "source.spm", spm), part("tokenizer", "tokenizer.json", tokenizer),
                part("encoder", "encoder_model_quantized.onnx", encoder),
                part("decoder", "decoder_model_merged_quantized.onnx", decoder)))
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
