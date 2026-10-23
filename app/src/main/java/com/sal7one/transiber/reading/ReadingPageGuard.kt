package com.sal7one.transiber.reading

import kotlin.math.abs

/**
 * A bounded admission check for clean, cropped page signatures, before expensive OCR.
 *
 * Motion frames contain Hearth's translated pixels; clean capture signatures do not.
 * Comparing clean pages prevents an overlay disappearing or a toolbar opening from
 * translating the same original page again. Manual refresh intentionally bypasses
 * this check. Neither captures nor translated text are retained here.
 */
internal class ReadingPageGuard {
    internal class Ticket internal constructor(internal val generation: Long, internal val signature: IntArray)

    private var generation = 0L
    private var active: Ticket? = null
    private var completed: IntArray? = null

    /** Null means this page is already completed or currently being read. */
    fun begin(signature: IntArray, manual: Boolean = false): Ticket? {
        require(signature.isNotEmpty())
        require(signature.all {it in 0..255})
        if(!manual && (completed?.let {samePage(it,signature)} == true ||
                    active?.signature?.let {samePage(it,signature)} == true)) return null
        return Ticket(++generation,signature.copyOf()).also {active=it}
    }

    fun isCurrent(ticket: Ticket): Boolean = active === ticket && ticket.generation == generation

    /** A cancelled or superseded native result must not become the displayed page. */
    fun complete(ticket: Ticket): Boolean {
        if(!isCurrent(ticket))return false
        completed=ticket.signature
        active=null
        return true
    }

    /** Keep the completed page so returning from controls can reuse it. */
    fun cancel() { generation++;active=null }

    /** Changing OCR language, translator or selected screen area requires new work. */
    fun reset() { cancel();completed=null }

    private fun samePage(first: IntArray, second: IntArray): Boolean {
        if(first.size != second.size)return false
        var changed=0
        var totalDifference=0L
        // Allow tiny capture/color noise and an isolated caret. The absolute cap
        // keeps a large signature from swallowing a meaningful small text change.
        val tolerated=minOf(8,first.size/200)
        for(index in first.indices) {
            val difference=abs(first[index]-second[index])
            totalDifference+=difference
            if(difference>6 && ++changed>tolerated)return false
        }
        return totalDifference <= first.size.toLong()*2
    }
}
