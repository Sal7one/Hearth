package com.sal7one.transiber.reading

/** Clock supplied by the capture service: testable, bounded, no Android dependencies. */
internal class ReadingTrigger {
    var mode = "manual"
    var settleMs = 700L
    var intervalMs = 2000L
    private var changedAt = Long.MIN_VALUE
    private var lastRequest = -100_000L
    private var changed = false
    fun movement(now: Long) { changed = true; changedAt = now }
    fun ready(now: Long): Boolean = mode == "page" && changed &&
        now-changedAt >= settleMs && now-lastRequest >= intervalMs
    fun accepted(now: Long) { lastRequest=now; changed=false }
    fun reset() { changed=false }

    companion object {
        /** Migrate retired accessibility shortcuts; unknown values must not start capture automatically. */
        fun supportedMode(saved: String?): String = when(saved) {
            "page", "distance", "scrolls" -> "page"
            else -> "manual"
        }
    }
}
