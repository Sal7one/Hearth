package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.CancellableTextTranslator

/** No SDK, client or delegated download exists in the offline flavor. */
object PlatformTranslation {
    val available = false
    val languages: Set<String> = emptySet()
    suspend fun installed(): Set<String> = emptySet()
    suspend fun download(code: String): Unit = error("ML Kit packs require the network-enabled app")
    suspend fun remove(code: String): Unit = error("ML Kit is unavailable in the offline app")
    fun open(): CancellableTextTranslator = error("ML Kit is unavailable in the offline app")
}
