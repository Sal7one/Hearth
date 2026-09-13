package com.sal7one.transiber.ocr

import android.content.Context
import com.sal7one.common_jni.ocr.PaddleOcr
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class OcrAsset(val id: String, val repository: String, val revision: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/PaddlePaddle/$repository/resolve/$revision/inference.onnx"
    val filename get() = "$repository.onnx"
}
internal data class OcrProfile(val id: String, val label: String, val languages: Set<String>, val asset: OcrAsset)
internal object OcrCatalog {
    val detector = OcrAsset("ocr-detector", "PP-OCRv5_mobile_det_onnx", "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",4826518,"a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d")
    val profiles = listOf(
        OcrProfile("latin","Latin · English & European languages",setOf("en","fr","de","es","it","pt","nl","sv"), OcrAsset("ocr-latin","latin_PP-OCRv5_mobile_rec_onnx","89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",8042023,"7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498")),
        OcrProfile("cjk","Chinese · English · Japanese",setOf("zh","en","ja"),OcrAsset("ocr-cjk","PP-OCRv5_mobile_rec_onnx","ed152b8b495f84de93cda5709d768548a9127622",16534782,"da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092")),
        OcrProfile("arabic","Arabic",setOf("ar"),OcrAsset("ocr-arabic","arabic_PP-OCRv5_mobile_rec_onnx","14aaedcd75825982689ecf5cd64ab33ee083215a",7998947,"799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110")),
        OcrProfile("cyrillic","Russian · Ukrainian · Belarusian",setOf("ru","uk","be"),OcrAsset("ocr-cyrillic","eslav_PP-OCRv5_mobile_rec_onnx","9a32171fc5718746875e1a261818884517975013",7887627,"b3018ef2b09a0250b6e0c8e871c927098363e5fd4df890cc68e8358eb0aaf1bd")),
    )
    val assets get() = listOf(detector) + profiles.map { it.asset }
    fun find(id: String) = assets.firstOrNull { it.id == id }
    fun profile(id: String) = profiles.firstOrNull { it.id == id } ?: error("Unknown OCR profile: $id")
}
/** Shared download/import sink: content identity, bounded staging, atomic publication. */
internal class OcrModels(private val root: File) {
    fun file(asset: OcrAsset) = File(root, "${asset.id}.onnx")
    fun installed(asset: OcrAsset) = file(asset).let { it.isFile && it.length() == asset.bytes }
    fun ready(profile: OcrProfile) = installed(OcrCatalog.detector) && installed(profile.asset)
    fun missing(profile: OcrProfile) = listOf(OcrCatalog.detector,profile.asset).filterNot(::installed)
    fun import(input: InputStream, expected: OcrAsset? = null, cancelled: () -> Unit = {}): OcrAsset {
        root.mkdirs(); val temp = File.createTempFile("ocr-import-", ".part", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while(true) { cancelled(); val count = input.read(buffer); if(count < 0) break
                    total += count; require(total <= (expected?.bytes ?: 32L*1024*1024)) { "OCR file exceeds supported model size" }
                    digest.update(buffer,0,count); out.write(buffer,0,count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val asset = OcrCatalog.assets.firstOrNull { it.bytes == total && it.sha256 == sha } ?: error("OCR model checksum is not in the supported PaddleOCR catalog. Download the linked ONNX export.")
            require(expected == null || expected.id == asset.id) { "Downloaded OCR model does not match the selected model" }
            cancelled(); Files.move(temp.toPath(),file(asset).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return asset
        } finally { temp.delete() }
    }
    fun open(context: Context, profile: OcrProfile): PaddleOcr {
        for(asset in listOf(OcrCatalog.detector,profile.asset)) {
            val path = file(asset); check(installed(asset)) { "Download or import ${asset.filename} first" }
            val hash = path.inputStream().use { input -> val digest=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)};digest.digest().joinToString(""){"%02x".format(it)} }
            check(hash == asset.sha256) { "Installed OCR model checksum failed: ${asset.id}" }
        }
        val array=JSONArray(context.assets.open("ocr/${profile.id}.json").bufferedReader().use { it.readText() })
        return PaddleOcr(file(OcrCatalog.detector),file(profile.asset),(0 until array.length()).map(array::getString))
    }
}
