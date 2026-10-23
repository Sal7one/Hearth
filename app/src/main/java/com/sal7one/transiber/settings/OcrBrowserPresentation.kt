package com.sal7one.transiber.settings

import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.ocr.OcrProfile

/** Search includes script/language names and exact pinned export names, never inferred coverage. */
internal fun OcrProfile.matchesBrowser(query: String, family: String): Boolean =
    (family == "any" || family == this.family) && ModelSearch.matches(
        query, id, label, description, engine, ModelSearch.languageTerms(languages),
        assets.joinToString(" ") { "${it.id} ${it.filename} ${it.remoteFile} ${it.owner}/${it.repository} ${it.license}" },
    )
