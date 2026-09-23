package com.sal7one.transiber.benchmark

import com.sal7one.transiber.i18n.UiText

/** Localized benchmark copy; engine errors and user/reference text remain verbatim. */
internal fun UiText.bench(en: String, ar: String, zh: String): String = when (locale.language) {
    "ar" -> ar
    "zh" -> zh
    else -> en
}
