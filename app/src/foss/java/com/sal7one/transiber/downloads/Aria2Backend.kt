package com.sal7one.transiber.downloads

import android.content.Context

internal object Aria2Backend {
    fun available(context: Context) = false
    suspend fun prepare(context: Context): Aria2Runtime? = null
}
