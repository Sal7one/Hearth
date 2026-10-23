package com.sal7one.transiber.settings

import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.ocr.OcrProfile

/** Search includes script/language names and exact pinned export names, never inferred coverage. */
internal fun OcrProfile.matchesBrowser(query: String, family: String): Boolean =
    (family == "any" || family == this.family) && ModelSearch.matches(
        query, id, label, description, engine, ModelSearch.languageTerms(languages),
        assets.joinToString(" ") { "${it.id} ${it.filename} ${it.remoteFile} ${it.owner}/${it.repository} ${it.license}" },
    )

/** OCR follows the text being read, never the translator's output language. */
internal fun recommendedOcrReader(source: String, selected: String?, profiles: List<OcrProfile> = com.sal7one.transiber.ocr.OcrCatalog.profiles): OcrProfile? {
    val current = profiles.firstOrNull { it.id == selected }
    if (current != null && source == "ja" && current.family in setOf("manga", "meiki") && source in current.languages) return current
    return profiles.firstOrNull { it.engine == "paddle" && source in it.languages }
}

/** A persisted search/family/installed filter must not hide the route back to useful readers. */
internal fun hiddenRelevantOcrReaders(source: String, selected: String?, shown: List<OcrProfile>,
    profiles: List<OcrProfile> = com.sal7one.transiber.ocr.OcrCatalog.profiles): Int {
    val visible = shown.map { it.id }.toSet()
    return profiles.count { it.id !in visible && (it.id == selected || source in it.languages) }
}
