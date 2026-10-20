package com.sal7one.common_jni.translation

import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.speech.TranslationDirection
import java.io.File

/** Exact required files; each directional package is installed atomically. */
data class Ct2File(val name: String, val bytes: Long, val sha256: String, val repo: String, val revision: String) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$name"
}
data class Ct2ModelSpec(val id: String, val label: String, val tokenizerMode: Int,
    val sources: Set<String>, val targets: Set<String>, val files: List<Ct2File>, val license: String, val modelCard: String,
    val downloadEnabled: Boolean = true, val groupId: String = id, val precisionLabel: String = "") {
    val bytes get() = files.sumOf { it.bytes }
    val selectionLabel get() = label + precisionLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
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
        Ct2ModelSpec("ct2-jisr-50m-en-ar", "Jisr 50M · English → Arabic dialects", 4, setOf("en"), setOf("ar"), listOf(
            Ct2File("config.json", 226L, "99fa08f9ae4fe585ace28258a3420d4d19d59e2b99d3e8dd2bc7dff70aa408cf", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("model.bin", 194430755L, "5b0124527a4a9763d819d8087c08ed289df0ce02d926737313aac5492056353c", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("shared_vocabulary.json", 1318566L, "12b45628eae6d2949aa85e430d6a2c7f033400f894f9ec2e521466aa82006e5a", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("tokenizer.json", 2670172L, "6f45717637add9564d0bd905209208d1aafa55a2e4ec3b8a817309e0384cb307", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("LICENSE", 11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("NOTICE", 562L, "bce7092fadb678426aaba270129f02b3a3633df9962ee3f438c781e6eccf6372", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
        ), "Apache-2.0", "https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects", downloadEnabled = false, groupId = "ct2-jisr-50m-en-ar", precisionLabel = "FP32"),
        Ct2ModelSpec("ct2-jisr-50m-en-ar-int8", "Jisr 50M · English → Arabic dialects", 2, setOf("en"), setOf("ar"), listOf(
            Ct2File("config.json", 226L, "99fa08f9ae4fe585ace28258a3420d4d19d59e2b99d3e8dd2bc7dff70aa408cf", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("model.bin", 50619043L, "6b8b5286c7edc7eb711a17c0b9c644314e41740d1e4e7d93f799980771791375", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("shared_vocabulary.json", 1318566L, "12b45628eae6d2949aa85e430d6a2c7f033400f894f9ec2e521466aa82006e5a", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("tokenizer.json", 2670172L, "6f45717637add9564d0bd905209208d1aafa55a2e4ec3b8a817309e0384cb307", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("LICENSE", 11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
            Ct2File("NOTICE", 559L, "d83d54c3ffa0eba669e616af1a0ab8fa775059a6d916206b51b00b7372f88d38", "oddadmix/Jisr-MT-50M-AllDialects", "8803d9d4e3b53e96783968237aa32425eb83922e"),
        ), "Apache-2.0", "https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects", downloadEnabled = false, groupId = "ct2-jisr-50m-en-ar", precisionLabel = "INT8"),
        Ct2ModelSpec("ct2-jisr-50m-ar-en", "Jisr 50M · Arabic dialects → English", 5, setOf("ar"), setOf("en"), listOf(
            Ct2File("config.json", 226L, "99fa08f9ae4fe585ace28258a3420d4d19d59e2b99d3e8dd2bc7dff70aa408cf", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("model.bin", 194407459L, "ca3cb2edaff87fddf715db8fcdea9258b47a926624f662aab01e31decd753805", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("shared_vocabulary.json", 1318394L, "38b7aaf9fd81d8e6b2cae4603966e768813377b628ba86ae9b1dab28c3929e53", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("tokenizer.json", 2667790L, "7ab7a5ec74e7c2a1671c051fe91aaa3d92e3657a75f6867cd584d3b4307dcc9b", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("LICENSE", 11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("NOTICE", 562L, "42b536894f16165d948f83792e502e14ff01aa59a237544b912b16b678ffb282", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
        ), "Apache-2.0", "https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En", downloadEnabled = false, groupId = "ct2-jisr-50m-ar-en", precisionLabel = "FP32"),
        Ct2ModelSpec("ct2-jisr-50m-ar-en-int8", "Jisr 50M · Arabic dialects → English", 3, setOf("ar"), setOf("en"), listOf(
            Ct2File("config.json", 226L, "99fa08f9ae4fe585ace28258a3420d4d19d59e2b99d3e8dd2bc7dff70aa408cf", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("model.bin", 50613167L, "fe73eb55916e327c47b9d346f6b2d1f734d3d34533e3a97c55dd74ba4b0e1b19", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("shared_vocabulary.json", 1318394L, "38b7aaf9fd81d8e6b2cae4603966e768813377b628ba86ae9b1dab28c3929e53", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("tokenizer.json", 2667790L, "7ab7a5ec74e7c2a1671c051fe91aaa3d92e3657a75f6867cd584d3b4307dcc9b", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("LICENSE", 11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
            Ct2File("NOTICE", 559L, "02665d596142b410fc5f4a2b239ab453619125286e18784fbd7ec630ae94db52", "oddadmix/Jisr-MT-50M-Dialects2En", "2cfe89dcfaba280c81bc0b9da34e9dc096795405"),
        ), "Apache-2.0", "https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En", downloadEnabled = false, groupId = "ct2-jisr-50m-ar-en", precisionLabel = "INT8"),
    )
    fun find(id: String): Ct2ModelSpec? = models.firstOrNull { it.id == id }
}
