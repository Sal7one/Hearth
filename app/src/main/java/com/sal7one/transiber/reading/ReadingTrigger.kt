package com.sal7one.transiber.reading

/** Clock supplied by the capture service: testable, bounded, no Android dependencies. */
internal class ReadingTrigger {
    var mode = "manual"
    var settleMs = 900L
    var intervalMs = 4000L
    private var changedAt: Long? = null
    private var lastRequest: Long? = null
    fun movement(now: Long) { changedAt = now }
    fun ready(now: Long): Boolean {
        val movement=changedAt ?: return false
        val last=lastRequest
        return mode == "page" && now >= movement && now-movement >= settleMs.coerceAtLeast(0) &&
            (last == null || (now >= last && now-last >= intervalMs.coerceAtLeast(0)))
    }
    fun accepted(now: Long) { lastRequest=now; changedAt=null }
    /** Closing controls cancels pending UI motion without scheduling another page. */
    fun reset() { changedAt=null }

    companion object {
        /** Migrate retired accessibility shortcuts; unknown values must not start capture automatically. */
        fun supportedMode(saved: String?): String = when(saved) {
            "page", "distance", "scrolls" -> "page"
            else -> "manual"
        }
    }
}
