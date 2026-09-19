package com.sal7one.transiber.reading

import kotlin.math.abs

/** Clock supplied by the capture service: testable, bounded, no Android dependencies. */
internal class ReadingTrigger {
    var mode = "manual"
    var settleMs = 700L
    var intervalMs = 2000L
    var screenDistance = .75f
    var scrollBursts = 2
    private var changedAt = Long.MIN_VALUE
    private var lastRequest = -100_000L
    private var distance = 0f
    private var bursts = 0
    private var lastScroll = -100_000L
    private var direction = 0
    private var changed = false
    fun movement(now: Long) { changed = true; changedAt = now }
    fun scroll(delta: Int, height: Int, now: Long) {
        movement(now)
        if (now-lastScroll > settleMs) bursts++
        lastScroll=now
        if (height <= 0 || delta == 0 || abs(delta.toLong()) > height * 5L) return
        val sign=if(delta>0)1 else -1
        if(direction!=0 && direction!=sign) distance=0f
        direction=sign
        distance += abs(delta.toFloat())/height
    }
    fun ready(now: Long): Boolean = changed && now-changedAt >= settleMs && now-lastRequest >= intervalMs && when(mode) {
        "page" -> true
        "distance" -> distance >= screenDistance
        "scrolls" -> bursts >= scrollBursts
        else -> false
    }
    fun accepted(now: Long) { lastRequest=now; changed=false;distance=0f;bursts=0;direction=0 }
    fun reset() { changed=false;distance=0f;bursts=0;direction=0;lastScroll=-100_000L }
}

internal object PageDifference {
    fun changed(previous: IntArray?, current: IntArray): Boolean {
        if(previous==null || previous.size!=current.size)return true
        // A coarse luminance grid ignores tiny antialiasing fluctuations, not full page changes.
        return current.indices.count { abs(current[it]-previous[it])>22 } > current.size/25
    }
}
