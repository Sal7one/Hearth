package com.sal7one.transiber.caption

/** Immutable presentation snapshot. Holding this never pauses the audio engine. */
internal data class CaptionReadingSnapshot(
    val history: List<CaptionLine>,
    val partial: String,
    val partialTranslation: String?,
)

internal object CaptionReading {
    const val DEFAULT_PREVIOUS_LINES = 4
    const val MAX_HISTORY = 120

    fun snapshot(state: CaptionEngineController.State, showPartial: Boolean) = CaptionReadingSnapshot(
        state.history.takeLast(MAX_HISTORY).toList(),
        if (showPartial) state.partial else "",
        if (showPartial) state.partialTranslation else null,
    )

    // The latest final remains visible while the next partial starts, even at zero history.
    fun visibleFinals(snapshot: CaptionReadingSnapshot, previousLines: Int, reading: Boolean) =
        if (reading) snapshot.history else snapshot.history.takeLast(previousLines.coerceIn(0, 8) + 1)
}
