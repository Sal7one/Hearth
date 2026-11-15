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

internal fun mangaLinePageCard() = DesktopModelCard(
    id = MangaArtifacts.lineProfileId, name = "Fast manga text lines", family = "PP-OCRv6 Manga", stage = null,
    engineId = "manga-page", languages = MangaArtifacts.lineLanguages, source = MangaArtifacts.lineModelCard,
    license = "Apache-2.0", note = "Read horizontal and vertical Japanese and Chinese text lines with a small CPU model. Uses spatial reading order and Hearth’s selected translator.",
    options = mapOf("purpose" to "ocr", "sourceLanguage" to "ja"),
    artifacts = MangaArtifacts.lineFiles.map { DesktopModelArtifact(it.filename, it.url, it.bytes, it.sha256) },
)
