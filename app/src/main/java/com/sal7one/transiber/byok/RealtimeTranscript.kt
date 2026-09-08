package com.sal7one.transiber.byok

/** Server finals can arrive out of order. Keep commit order and per-item deltas. */
internal class RealtimeTranscript {
    private data class Item(var text: String = "", var final: String? = null)
    private val items = linkedMapOf<String, Item>()
    private val completed = ArrayDeque<String>()
    val partial: String get() = items.values.lastOrNull { it.final == null }?.text.orEmpty()

    fun begin(id: String) {
        require(id.isNotBlank()) { "Transcription event is missing item_id" }
        if (id in completed) return
        check(items.size < 32 || id in items) { "Transcription backlog exceeded 32 unfinished turns" }
        items.getOrPut(id) { Item() }
    }
    fun append(id: String, delta: String) {
        begin(id)
        items[id]?.let { it.text = (it.text + delta).takeLast(2_000) }
    }
    fun finish(id: String, text: String): List<String> {
        begin(id)
        items[id]?.final = text
        val ready = mutableListOf<String>()
        while (items.isNotEmpty()) {
            val head = items.entries.first()
            val final = head.value.final ?: break
            items.remove(head.key)
            completed.addLast(head.key)
            if (completed.size > 64) completed.removeFirst()
            if (final.isNotBlank()) ready.add(final)
        }
        return ready
    }
}
