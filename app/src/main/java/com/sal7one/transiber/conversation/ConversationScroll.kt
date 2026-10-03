package com.sal7one.transiber.conversation

/** Follow the measured bottom, including a growing card taller than the viewport. */
internal sealed interface ConversationScroll {
    data object Stay : ConversationScroll
    data class ShowLast(val index: Int) : ConversationScroll
    data class RevealBottom(val pixels: Int) : ConversationScroll
}
internal fun conversationFollowAfterScroll(current: Boolean, scrolling: Boolean,
    canScrollForward: Boolean, appScrolling: Boolean): Boolean = when {
    scrolling && !appScrolling -> false
    !scrolling && !canScrollForward -> true
    else -> current
}
internal fun conversationScroll(follow: Boolean, scrolling: Boolean, total: Int,
    lastVisibleIndex: Int?, lastBottom: Int, viewportEnd: Int): ConversationScroll = when {
    !follow || scrolling || total <= 0 -> ConversationScroll.Stay
    lastVisibleIndex != total - 1 -> ConversationScroll.ShowLast(total - 1)
    lastBottom > viewportEnd -> ConversationScroll.RevealBottom(lastBottom - viewportEnd)
    else -> ConversationScroll.Stay
}
