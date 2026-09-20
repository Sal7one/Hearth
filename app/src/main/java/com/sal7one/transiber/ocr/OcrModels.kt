package com.sal7one.transiber.ocr

import android.content.Context
import com.sal7one.common_jni.ocr.PaddleOcr
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.JapaneseOcr
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class OcrAsset(val id: String, val repository: String, val revision: String, val bytes: Long, val sha256: String, val owner: String = "PaddlePaddle", val remoteFile: String = "inference.onnx") {
    val url get() = "https://huggingface.co/$owner/$repository/resolve/$revision/$remoteFile"
    val filename get() = if(owner == "PaddlePaddle") "$repository.onnx" else "$id.onnx"
}
internal data class OcrProfile(val id: String, val label: String, val languages: Set<String>, val asset: OcrAsset, val engine: String = "paddle", val extraAssets: List<OcrAsset> = emptyList(), val description: String = "Horizontal printed text · Apache-2.0") {
    val assets get() = if(engine == "paddle") listOf(OcrCatalog.detector,asset) else listOf(asset)+extraAssets
    val live get() = engine == "paddle"
}
internal object OcrCatalog {
    val detector = OcrAsset("ocr-detector", "PP-OCRv5_mobile_det_onnx", "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",4826518,"a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d")
    // Publisher language table, checked 2026-09-20; tied to each pinned recognition artifact.
    // https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.html
    // Latin profile's Serbian/Kurdish are Latin-script; choose the Arabic reader for Arabic-script Kurdish.
    val profiles = listOf(
        OcrProfile("latin","Latin · English & European languages","en fr de af it es bs pt cs cy da et ga hr uz hu sr id oc is lt mi ms nl no pl sk sl sq sv sw fil tr la az ku lv mt pi ro vi fi eu gl lb rm ca qu".split(' ').toSet(), OcrAsset("ocr-latin","latin_PP-OCRv5_mobile_rec_onnx","89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",8042023,"7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498")),
        OcrProfile("cjk","Chinese · English · Japanese",setOf("zh","en","ja"),OcrAsset("ocr-cjk","PP-OCRv5_mobile_rec_onnx","ed152b8b495f84de93cda5709d768548a9127622",16534782,"da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092")),
        OcrProfile("arabic","Arabic script · Arabic, Persian, Urdu & more","ar fa ug ur ps ku sd bal en".split(' ').toSet(),OcrAsset("ocr-arabic","arabic_PP-OCRv5_mobile_rec_onnx","14aaedcd75825982689ecf5cd64ab33ee083215a",7998947,"799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110")),
        OcrProfile("cyrillic","Russian · Ukrainian · Belarusian",setOf("ru","uk","be","en"),OcrAsset("ocr-cyrillic","eslav_PP-OCRv5_mobile_rec_onnx","9a32171fc5718746875e1a261818884517975013",7887627,"b3018ef2b09a0250b6e0c8e871c927098363e5fd4df890cc68e8358eb0aaf1bd")),
        OcrProfile("manga","Japanese · Manga bubble",setOf("ja"),OcrAsset("ocr-manga-encoder","manga-ocr-base-ONNX","f9023406bb2f6b17df67bc4a327c56ecd20611f0",86967805,"a73e7a9959f3412f4d0ab60c8cd0f71c29e7e29a2e52a1e184ad6f2be3b892e3","onnx-community","onnx/encoder_model_uint8.onnx"),"manga",listOf(OcrAsset("ocr-manga-decoder","manga-ocr-base-ONNX","f9023406bb2f6b17df67bc4a327c56ecd20611f0",29627936,"2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27","onnx-community","onnx/decoder_model_int8.onnx")),"Draw around one bubble. Vertical/horizontal Japanese and furigana · Experimental · Apache-2.0"),
        OcrProfile("meiki","Japanese · Meiki text lines",setOf("ja"),OcrAsset("ocr-meiki-detector","meiki.text.detect.v0","a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6",14503825,"40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b","rtr46","meiki.text.detect.v0.1.960x544.onnx"),"meiki",listOf(OcrAsset("ocr-meiki-reader","meiki.txt.recognition.v0","a28cf5874dc2438ebb1c86336be26bcec51e3375",18593254,"3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000","rtr46","meiki.text.rec.v0.960x32.onnx"),OcrAsset("ocr-meiki-vertical","meiki.txt.recognition.v0","a28cf5874dc2438ebb1c86336be26bcec51e3375",12872961,"2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a","rtr46","meiki.text.rec.v0.vertical.32x480.onnx")),"Japanese line detection and recognition. Vertical reader is beta · Experimental · LGPL-3.0 model weights"),
    )
    val assets get() = profiles.flatMap { it.assets }.distinctBy { it.id }
    fun find(id: String) = assets.firstOrNull { it.id == id }
    fun profile(id: String) = profiles.firstOrNull { it.id == id } ?: error("Unknown OCR profile: $id")
}
/** Shared download/import sink: content identity, bounded staging, atomic publication. */
internal class OcrModels(private val root: File) {
    fun file(asset: OcrAsset) = File(root, "${asset.id}.onnx")
    fun installed(asset: OcrAsset) = file(asset).let { it.isFile && it.length() == asset.bytes }
    fun ready(profile: OcrProfile) = profile.assets.all(::installed)
    fun missing(profile: OcrProfile) = profile.assets.filterNot(::installed)
    fun import(input: InputStream, expected: OcrAsset? = null, cancelled: () -> Unit = {}): OcrAsset {
        root.mkdirs(); val temp = File.createTempFile("ocr-import-", ".part", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while(true) { cancelled(); val count = input.read(buffer); if(count < 0) break
                    total += count; require(total <= (expected?.bytes ?: 128L*1024*1024)) { "OCR file exceeds supported model size" }
                    digest.update(buffer,0,count); out.write(buffer,0,count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val asset = OcrCatalog.assets.firstOrNull { it.bytes == total && it.sha256 == sha } ?: error("OCR model checksum is not in the supported OCR catalog. Download the linked ONNX export.")
            require(expected == null || expected.id == asset.id) { "Downloaded OCR model does not match the selected model" }
            cancelled(); Files.move(temp.toPath(),file(asset).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return asset
        } finally { temp.delete() }
    }
    fun open(context: Context, profile: OcrProfile): OcrEngine {
        for(asset in profile.assets) {
            val path = file(asset); check(installed(asset)) { "Download or import ${asset.filename} first" }
            val hash = path.inputStream().use { input -> val digest=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)};digest.digest().joinToString(""){"%02x".format(it)} }
            check(hash == asset.sha256) { "Installed OCR model checksum failed: ${asset.id}" }
        }
        if(profile.engine == "meiki")return JapaneseOcr(2,file(profile.asset),file(profile.extraAssets[0]),file(profile.extraAssets[1]))
        val array=JSONArray(context.assets.open("ocr/${profile.id}.json").bufferedReader().use { it.readText() })
        if(profile.engine == "manga")return JapaneseOcr(1,file(profile.asset),file(profile.extraAssets[0]),vocabulary=(0 until array.length()).map(array::getString))
        return PaddleOcr(file(OcrCatalog.detector),file(profile.asset),(0 until array.length()).map(array::getString))
    }
}
