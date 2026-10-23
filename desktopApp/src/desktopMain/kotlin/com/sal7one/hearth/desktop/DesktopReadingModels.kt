package com.sal7one.hearth.desktop

internal val readingModelIds=setOf("manga-page","manga-koharu-page","manga-koharu-clean-page")
internal fun selectedReadingModel(ui:Map<String,String>):String = ui["reading-model-id"]?.takeIf { it in readingModelIds } ?: "manga-page"
