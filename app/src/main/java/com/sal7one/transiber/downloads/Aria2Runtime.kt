package com.sal7one.transiber.downloads

import java.io.File
import com.sal7one.hearth.core.downloads.Aria2ProbeException

internal data class Aria2Runtime(val executable: File, val environment: Map<String, String>)

internal data class Aria2RuntimeSelection(val runtime: Aria2Runtime?, val notice: String? = null)

/** Only an Automatic pre-transfer capability failure can choose the normal verified HTTPS transport. */
internal suspend fun selectAria2Runtime(backend: String, prepare: suspend () -> Aria2Runtime?): Aria2RuntimeSelection {
    require(backend in setOf("auto", "aria2", "http"))
    if (backend == "http") return Aria2RuntimeSelection(null)
    return try { Aria2RuntimeSelection(prepare()) }
    catch (error: Aria2ProbeException) {
        if (backend != "auto") throw error
        Aria2RuntimeSelection(null, "aria2 could not run: ${error.message}. Using the standard HTTPS downloader; model verification still applies.")
    }
}
