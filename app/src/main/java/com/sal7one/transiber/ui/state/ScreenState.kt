package com.sal7one.transiber.ui.state

/**
 * The one screen-state convention (utils-bible: one convention enforced by
 * code). Every ported ViewModel exposes a single ScreenState<T> instead of
 * scattered bare flows: Loading / Ready(T) / Empty / Error(reason).
 *
 * - Empty is a FIRST-CLASS state (no items yet), never an Error.
 * - Error carries a user-presentable reason; recovery (retry) is rendered
 *   by ScreenStateScaffold, so no screen can forget its error path.
 */
sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data class Ready<T>(val value: T) : ScreenState<T>
    data object Empty : ScreenState<Nothing>
    data class Error(val reason: String) : ScreenState<Nothing>

    val isReady: Boolean get() = this is Ready
}

/** Maps the payload while preserving Loading/Empty/Error untouched. */
inline fun <T, R> ScreenState<T>.map(transform: (T) -> R): ScreenState<R> = when (this) {
    is ScreenState.Ready -> ScreenState.Ready(transform(value))
    is ScreenState.Loading -> this
    is ScreenState.Empty -> this
    is ScreenState.Error -> this
}
