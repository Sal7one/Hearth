package com.sal7one.transiber.conversation

import java.util.UUID

enum class TurnStatus { LISTENING, TRANSLATING, COMPLETE, ERROR, INTERRUPTED }

data class ConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    val speaker: Int,
    val source: String,
    val target: String,
    val original: String = "",
    val translation: String = "",
    val status: TurnStatus = TurnStatus.LISTENING,
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
    val route: String = "",
)

data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Conversation",
    val first: String = "ar",
    val second: String = "en",
    val turns: List<ConversationTurn> = emptyList(),
    val created: Long = System.currentTimeMillis(),
)

/** Stable identities guard a late result after deletion, switching history or a new turn. */
internal fun ConversationSession.updateTurn(sessionId: String, turn: ConversationTurn): ConversationSession =
    if (id != sessionId || turns.none { it.id == turn.id }) this
    else copy(turns = turns.map { if (it.id == turn.id) turn else it })

internal fun ConversationSession.interrupted(): ConversationSession = copy(turns = turns.map {
    if (it.status == TurnStatus.LISTENING || it.status == TurnStatus.TRANSLATING)
        it.copy(status = TurnStatus.INTERRUPTED, error = "Interrupted. Original text has been kept; retry translation when ready.")
    else it
})

internal fun ConversationSession.exportText(): String = buildString {
    appendLine(title)
    turns.forEach {
        appendLine("\n${if (it.speaker == 0) "Me" else "Them"} · ${it.source} → ${it.target}")
        appendLine(it.original)
        if (it.translation.isNotBlank()) appendLine(it.translation)
        it.error?.let(::appendLine)
    }
}
