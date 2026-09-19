package com.sal7one.transiber.ocr

/** Camera text must settle before automatic translation; capture commits immediately. */
internal class OcrStability {
    private var candidate = ""
    private var count = 0
    private var committed: String? = null
    fun observe(text: String, captured: Boolean): Boolean {
        // A user capture/retry is a new request even if its text has not changed.
        if (captured) { candidate=text;count=1;committed=text;return true }
        if (text == candidate) count++ else { candidate = text; count = 1; committed = null }
        if ((captured || count >= 2) && committed != text) { committed = text; return true }
        return false
    }
}
