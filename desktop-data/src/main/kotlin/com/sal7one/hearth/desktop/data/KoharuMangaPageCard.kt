package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.vision.MangaArtifacts

internal fun koharuMangaPageCard() = DesktopModelCard(
    id="manga-koharu-page", name="Koharu Japanese manga segmentation", family="Koharu RF-DETR + Manga OCR",stage=null,
    engineId="manga-page", languages=setOf("ja"),
    source="https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx",
    license="Other · Manga109 academic-data terms; reader Apache-2.0",
    note="Koharu's RF-DETR instance segmentation: pixel masks, panels, bubbles and text. Larger CPU/RAM workload than the compact detector; experimental. Read the weight terms. Uses Hearth's selected translator. Neural inpainting is not included.",
    options=mapOf("purpose" to "ocr", "sourceLanguage" to "ja", "detector" to "koharu-rfdetr"),
    artifacts=MangaArtifacts.filesForKoharu.map { DesktopModelArtifact("${it.id}.onnx",it.url,it.bytes,it.sha256) },
)

internal fun koharuCleanupMangaPageCard() = koharuMangaPageCard().copy(
    id="manga-koharu-clean-page",name="Koharu Japanese manga + neural cleanup",
    family="Koharu RF-DETR + Manga OCR + LaMa",
    note="Full mask detection, OCR, Hearth translation and LaMa neural text cleanup for artwork. Fast preliminary outlines, then a slower quality pass. CPU only, experimental and memory intensive. Detector weight terms include Manga109 academic-data conditions; reader and LaMa weights Apache-2.0.",
    options=mapOf("purpose" to "ocr", "sourceLanguage" to "ja", "detector" to "koharu-rfdetr", "cleanup" to "lama"),
    artifacts=MangaArtifacts.filesForKoharuCleanup.map { DesktopModelArtifact("${it.id}.onnx",it.url,it.bytes,it.sha256) },
)
