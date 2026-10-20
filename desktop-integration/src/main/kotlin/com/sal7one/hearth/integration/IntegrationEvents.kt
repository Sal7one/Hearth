package com.sal7one.hearth.integration

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class IntegrationEvent(val sequence: Long, val sessionId: String, val json: String)

/** Disconnect slow consumers explicitly; never build an unbounded per-browser audio/text queue. */
internal class IntegrationEvents {
    private val history = ArrayDeque<IntegrationEvent>()
    private val latest = mutableMapOf<String, IntegrationEvent>()
    private val listeners = mutableSetOf<Subscription>()
    private var sequence = 0L
    private var closed = false

    @Synchronized fun publish(sessionId: String, json: String) {
        if (closed) return
        val event = IntegrationEvent(++sequence, sessionId, json)
        latest[sessionId] = event
        history.addLast(event)
        while (history.size > 64) history.removeFirst()
        listeners.forEach { if (it.sessionId == null || it.sessionId == sessionId) it.offer(event) }
    }

    @Synchronized fun subscribe(sessionId: String?, after: Long?): Subscription {
        check(!closed) { "Integration server is closed" }
        check(listeners.size < 4) { "At most four event streams may be open" }
        val result = Subscription(sessionId)
        if (after != null) {
            if (history.firstOrNull()?.sequence?.let { after < it - 1 } == true || after > sequence) result.resync = true
            else history.filter { it.sequence > after && (sessionId == null || it.sessionId == sessionId) }.forEach(result::offer)
        }
        if (after == null || result.resync) result.initial = latest.values.filter { sessionId == null || it.sessionId == sessionId }.sortedBy { it.sequence }
        listeners += result
        return result
    }

    @Synchronized fun remove(subscription: Subscription) { listeners -= subscription; subscription.closed = true }
    @Synchronized fun forget(sessionId: String) { latest.remove(sessionId); history.removeAll { it.sessionId == sessionId } }
    @Synchronized fun snapshot(sessionId: String?) = latest.values.filter { sessionId == null || it.sessionId == sessionId }.sortedBy { it.sequence }
    @Synchronized fun close() { closed = true; listeners.forEach { it.closed = true }; listeners.clear(); history.clear(); latest.clear() }

    internal class Subscription(val sessionId: String?) {
        private val queue = LinkedBlockingQueue<IntegrationEvent>(64)
        @Volatile var closed = false
        @Volatile var resync = false
        var initial: List<IntegrationEvent> = emptyList()
        fun offer(event: IntegrationEvent) { if (!queue.offer(event)) { resync = true; closed = true } }
        fun poll(): IntegrationEvent? {
            if (resync) { queue.clear(); return null }
            return queue.poll(1, TimeUnit.SECONDS).takeUnless { resync }
        }
    }
}
