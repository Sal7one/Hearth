package com.sal7one.transiber.ocr

import com.sal7one.hearth.core.vision.MangaArtifacts

/** Each mode selects a complete, pinned page-reading bundle. */
internal enum class MangaPageMode(val profileId: String) {
    COMPACT("manga-page"),
    KOHARU("manga-koharu-page"),
    CLEANUP("manga-koharu-clean-page"),
}

internal val OcrProfile.mangaPageMode: MangaPageMode? get() = when {
    engine!="manga-page" -> null
    assets.any {it.id==MangaArtifacts.inpainter.id} -> MangaPageMode.CLEANUP
    assets.any {it.id==MangaArtifacts.koharuDetector.id} -> MangaPageMode.KOHARU
    else -> MangaPageMode.COMPACT
}

internal fun OcrSelection.withMangaPageMode(mode: MangaPageMode) = withProfile(mode.profileId)
