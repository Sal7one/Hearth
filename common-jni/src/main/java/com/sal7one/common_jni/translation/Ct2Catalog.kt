package com.sal7one.common_jni.translation

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.TranslationDirection
import java.io.File

/** Exact required files; each directional package is installed atomically. */
data class Ct2File(val name: String, val bytes: Long, val sha256: String, val repo: String, val revision: String) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$name"
}
data class Ct2ModelSpec(val id: String, val label: String, val tokenizerMode: Int,
    val sources: Set<String>, val targets: Set<String>, val files: List<Ct2File>, val license: String, val modelCard: String) {
    val bytes get() = files.sumOf { it.bytes }
    fun supports(source: String, target: String): Boolean {
        val from=TranslationLanguages.normalize(source);val to=TranslationLanguages.normalize(target)
        return from!=to && from in sources && to in targets
    }
    val directions: Set<TranslationDirection> by lazy { sources.flatMap { a -> targets.filter { supports(a,it) }.map { TranslationDirection(a,it) } }.toSet() }
    fun verify(root: File) {
        check(root.isDirectory) { "Install $label in Models first" }
        files.forEach { part -> val file=File(root,part.name)
            check(file.isFile && file.length()==part.bytes) { "Missing or truncated $label component: ${part.name}" }
            ModelIntegrity.inspect(file,part.sha256)
        }
    }
}
object Ct2Catalog {
    val models = listOf(
        Ct2ModelSpec("ct2-quickmt-en-ar", "QuickMT · English → Arabic", 0, setOf("en"), setOf("ar"), listOf(
            Ct2File("model.bin", 360843109L, "2b053bf9e989a3685f902eafac1d8128f8fcf833b90fdd142254f08bd73ca5af", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
            Ct2File("config.json", 223L, "66edc5d58ceb812a41b4b784497001f815c35400884f40984b19de29e1bc3d69", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
            Ct2File("source_vocabulary.json", 339573L, "59b2accfaba2b0a8171deed0cce56afdf41d5d724da68c63057b2035c512cbbe", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
            Ct2File("target_vocabulary.json", 731023L, "5437c4de92543211144f9319f234bf2bcacda2ea454c3b26453da97d4d76e833", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
            Ct2File("src.spm.model", 589684L, "acf054421b5ed52bd6aee14564fb8595b9a6b492df669e971d43fba9edb33c16", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
            Ct2File("tgt.spm.model", 642429L, "c7b8bf117088a901628696f60ddd15e047057ccbdcf9b0996ca19e2779a234c7", "quickmt/quickmt-en-ar", "37f2b37486c29b457971295d7711b5ac1869775d"),
        ), "CC-BY-4.0", "https://huggingface.co/quickmt/quickmt-en-ar"),
        Ct2ModelSpec("ct2-quickmt-ar-en", "QuickMT · Arabic → English", 0, setOf("ar"), setOf("en"), listOf(
            Ct2File("model.bin", 407101843L, "2c4e6f5d86de0d9d6c23c54768d7ab6bd20fb7a36099c987368d6a9f45c32da1", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
            Ct2File("config.json", 223L, "66edc5d58ceb812a41b4b784497001f815c35400884f40984b19de29e1bc3d69", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
            Ct2File("source_vocabulary.json", 1205230L, "1d31fe3264650f0e839b072f0d55059934fdd01b016b2616cf5afc66bd4ba8a4", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
            Ct2File("target_vocabulary.json", 546670L, "b099813467f7c6dbf00414324904782d55dac0f4c4635e440c4d36cb0f383825", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
            Ct2File("src.spm.model", 901383L, "1a510ccfeb5cf06584327b9c2482ed0a4111d06348a56e28d165165b6dabe520", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
            Ct2File("tgt.spm.model", 806180L, "8c793ee4a6d01a8b3fcd24d246a1580febf053747e44eefe99606974957cfdaa", "quickmt/quickmt-ar-en", "a1542c88d817d641a2acf2b8952bc892144882c0"),
        ), "CC-BY-4.0", "https://huggingface.co/quickmt/quickmt-ar-en"),
        Ct2ModelSpec("ct2-small100-int8", "SMaLL-100 · INT8", 1, setOf("af","am","ar","ast","az","ba","be","bg","bn","br","bs","ca","ceb","cs","cy","da","de","el","en","es","et","fa","ff","fi","fr","fy","ga","gd","gl","gu","ha","he","hi","hr","ht","hu","hy","id","ig","ilo","is","it","ja","jv","ka","kk","km","kn","ko","lb","lg","ln","lo","lt","lv","mg","mk","ml","mn","mr","ms","my","ne","nl","no","ns","oc","or","pa","pl","ps","pt","ro","ru","sd","si","sk","sl","so","sq","sr","ss","su","sv","sw","ta","th","fil","tn","tr","uk","ur","uz","vi","wo","xh","yi","yo","zh","zu"), setOf("af","am","ar","ast","az","ba","be","bg","bn","br","bs","ca","ceb","cs","cy","da","de","el","en","es","et","fa","ff","fi","fr","fy","ga","gd","gl","gu","ha","he","hi","hr","ht","hu","hy","id","ig","ilo","is","it","ja","jv","ka","kk","km","kn","ko","lb","lg","ln","lo","lt","lv","mg","mk","ml","mn","mr","ms","my","ne","nl","no","ns","oc","or","pa","pl","ps","pt","ro","ru","sd","si","sk","sl","so","sq","sr","ss","su","sv","sw","ta","th","fil","tn","tr","uk","ur","uz","vi","wo","xh","yi","yo","zh","zu"), listOf(
            Ct2File("model.bin", 338727246L, "24e8b51720b7267cfd8faacedce50128bf11c4327f039632a494deb5e1c421f6", "solavr/small100-ctranslate2-int8", "3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84"),
            Ct2File("config.json", 233L, "72901fbd8abd89fb5cf4a388f26fc681f5c4c58a1e1a88b30b879f107270e7ee", "solavr/small100-ctranslate2-int8", "3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84"),
            Ct2File("sentencepiece.bpe.model", 2423393L, "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a", "solavr/small100-ctranslate2-int8", "3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84"),
            Ct2File("shared_vocabulary.json", 2924622L, "18916ac68f9ac8fbd5250ca603dcb3861aac8672924d93a8dadc82eb3147f941", "solavr/small100-ctranslate2-int8", "3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84"),
        ), "MIT", "https://huggingface.co/alirezamsh/small100"),
    )
    fun find(id: String): Ct2ModelSpec? = models.firstOrNull { it.id == id }
}
