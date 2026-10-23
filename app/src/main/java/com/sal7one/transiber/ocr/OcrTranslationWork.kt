package com.sal7one.transiber.ocr

/** Progressive OCR can replace queued translation work without changing the page revision. */
internal class OcrTranslationWork {
    class Ticket internal constructor(internal val revision: Long)
    private var latest: Ticket? = null

    @Synchronized
    fun begin(revision: Long, currentRevision: () -> Long): Ticket? {
        if(revision!=currentRevision())return null
        return Ticket(revision).also {latest=it}
    }

    @Synchronized
    fun isCurrent(ticket: Ticket,currentRevision: () -> Long): Boolean =
        latest===ticket && ticket.revision==currentRevision()

    /** Publishing and invalidation share a lock, including the actual state update. */
    @Synchronized
    fun publish(ticket: Ticket,currentRevision: () -> Long,update: () -> Unit): Boolean {
        if(!isCurrent(ticket,currentRevision))return false
        update()
        return true
    }

    /** Only the newest queued request can make the page terminal. */
    @Synchronized
    fun finish(ticket: Ticket,currentRevision: () -> Long,update: () -> Unit): Boolean {
        if(!isCurrent(ticket,currentRevision))return false
        latest=null
        update()
        return true
    }

    @Synchronized
    fun invalidate() {latest=null}
}
