package com.sal7one.common_jni.translation

/** Publisher tag identities: in particular apc/ajp/ajp-ps must not be collapsed. */
object JisrDialects {
    val tags: Map<String, String> = linkedMapOf(
        "ar" to "ara", "arz" to "arz", "ary" to "ary", "arq" to "arq",
        "aeb" to "aeb", "ayl" to "ayl", "apc" to "apc", "ajp" to "ajp",
        "ajp-ps" to "ajp-ps", "ars" to "ars", "afb" to "afb", "apd" to "apd",
        "acq" to "acq", "acm" to "acm",
    )
    fun requireSupported(code: String): String = code.also {
        require(it in tags) { "Unsupported Jisr Arabic dialect: $it" }
    }
}
