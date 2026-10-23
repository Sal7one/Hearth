package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.vision.MangaArtifacts

/** OCR is an auxiliary image feature; it never adds a fourth stage to speech/translation/voice pipelines. */
internal fun mangaPageCard() = DesktopModelCard(
    id="manga-page",name="Automatic Japanese manga page",family="Manga OCR + RT-DETR-v2",stage=null,
    engineId="manga-page",languages=setOf("ja"),source="https://huggingface.co/ogkalu/comic-text-and-bubble-detector",
    license="Apache-2.0",note="Detect text and speech bubbles, highlight immediately, then read each region. CPU ONNX Runtime; experimental. Uses Manga OCR with its matching vocabulary. Translation uses your existing selected engine.",
    options=mapOf("purpose" to "ocr","sourceLanguage" to "ja"),
    artifacts=MangaArtifacts.files.map { DesktopModelArtifact("${it.id}.onnx",it.url,it.bytes,it.sha256) },
)
