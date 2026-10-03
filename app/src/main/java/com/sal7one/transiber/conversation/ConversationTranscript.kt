package com.sal7one.transiber.conversation

import com.sal7one.transiber.caption.CaptionLine
import com.sal7one.common_jni.translation.TextTranslationChunks

/** A turn owns its finals; the caption engine's short display tail is not a transcript archive. */
internal class ConversationTranscript {
    private val lines = linkedMapOf<Long, String>()
    private var characters = 0
    private var dirty = false
    private var cached = ""
    val text: String get() {
        if (dirty) { cached = lines.values.joinToString(" "); dirty = false }
        return cached
    }
    fun observe(history: List<CaptionLine>): String {
        for (line in history) {
            val value = line.original.trim()
            if (value.isEmpty() || lines[line.id] == value) continue
            val old = lines[line.id]
            val count = lines.size + if (old == null) 1 else 0
            val size = characters - (old?.length ?: 0) + value.length
            check(count <= 1024 && size + count - 1 <= TextTranslationChunks.MAX_TEXT_CHARACTERS) {
                "Conversation text limit reached (32000 characters or 1024 finals). Captured text is kept; start another turn to continue."
            }
            lines[line.id] = value
            characters = size
            dirty = true
        }
        return text
    }
}
