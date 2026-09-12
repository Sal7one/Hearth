package com.sal7one.transiber.caption

/** Silence before Stop must never skip flushing buffered speech or translation. */
internal object CaptionDrainPolicy {
    fun shouldWait(now: Long, started: Long, lastText: Long, speechPending: Boolean,
                   translationPending: Boolean, quietMs: Long, maximumMs: Long): Boolean =
        now - started < maximumMs &&
            (now - maxOf(started, lastText) < quietMs || speechPending || translationPending)
}
