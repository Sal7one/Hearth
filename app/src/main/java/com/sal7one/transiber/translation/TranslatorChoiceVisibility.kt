package com.sal7one.transiber.translation

import com.sal7one.transiber.models.ModelSearch

/** A missing current model remains repairable; browsing does not select a replacement. */
internal fun localTranslatorVisible(installed: Boolean, selected: Boolean, showUnavailable: Boolean,
    query: String, vararg searchTerms: String): Boolean =
    (installed || selected || showUnavailable) && ModelSearch.matches(query, *searchTerms)
