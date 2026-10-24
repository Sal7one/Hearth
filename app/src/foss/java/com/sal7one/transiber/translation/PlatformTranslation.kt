package com.sal7one.transiber.translation

import com.sal7one.common_jni.translation.CancellableTextTranslator
import kotlinx.coroutines.flow.asStateFlow

/** No SDK, client or delegated download exists in the offline flavor. */
object PlatformTranslation {
    val available = false
    val languages: Set<String> = emptySet()
    suspend fun installed(): Set<String> = emptySet()
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L).asStateFlow()
    suspend fun download(code: String, wifiOnly: Boolean = true): Unit = error("ML Kit packs require the network-enabled app")
    suspend fun downloadPair(source: String, target: String, wifiOnly: Boolean = true): Unit = error("ML Kit packs require the network-enabled app")
    suspend fun remove(code: String): Unit = error("ML Kit is unavailable in the offline app")
    fun open(): CancellableTextTranslator = error("ML Kit is unavailable in the offline app")
}
