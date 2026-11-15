package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.MangaPageMode
import com.sal7one.transiber.ocr.OcrProfile
import com.sal7one.transiber.ocr.OcrSelection

/** Explicit workflow choices; translator, destination and downloaded files remain shared. */
internal enum class ReadingContent(val key: String) {
    WEBSITES("websites"), MANGA("manga");

    fun accepts(profile: OcrProfile): Boolean = when (this) {
        WEBSITES -> profile.engine == "paddle"
        MANGA -> profile.pageReader || profile.engine in setOf("manga", "meiki")
    }

    companion object {
        fun selected(selection: OcrSelection): ReadingContent =
            if (MANGA.accepts(selection.profile)) MANGA else WEBSITES
    }
}

/** Restoring a workflow is an explicit tap, never a background model or destination migration. */
internal fun OcrSelection.forReadingContent(content: ReadingContent, savedProfile: String?, savedSource: String?,
    savedLegacyMangaPinned: Boolean = false): OcrSelection {
    if (content.accepts(profile) && source in profile.languages) return this
    val remembered = OcrCatalog.profiles.firstOrNull { it.id == savedProfile && content.accepts(it) && savedSource in it.languages }
    if (remembered != null) return copy(profileId = remembered.id, source = checkNotNull(savedSource),
        legacyMangaPinned = savedLegacyMangaPinned && remembered.id == MangaPageMode.COMPACT.profileId)
    return when (content) {
        ReadingContent.WEBSITES -> {
            val general = OcrCatalog.profiles.firstOrNull { content.accepts(it) && source in it.languages }
                ?: error("No general screen reader supports $source")
            copy(profileId = general.id, legacyMangaPinned = false)
        }
        ReadingContent.MANGA -> withProfile(MangaPageMode.FAST.profileId)
    }
}
