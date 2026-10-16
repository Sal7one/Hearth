package com.sal7one.transiber.ui

/** TV is a device capability, never inferred from a phone's landscape width. */
internal fun useTvInterface(television: Boolean, fullInterface: Boolean) = television && !fullInterface

/** Switching interface must not restore another screen's remembered inputs or focus. */
internal fun navigationStateKey(tv: Boolean, home: Boolean, page: Int): Any = when {
    tv -> if (home || page == 0) "tv-home" else "tv-setup"
    home -> "simple-home"
    else -> page
}
