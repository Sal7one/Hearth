package com.sal7one.hearth.desktop.ui.screens.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sal7one.hearth.desktop.ui.screens.companion.CompanionScreen

/** Conversation is a phone feature; this page shows it inside a device frame. */
@Composable
internal fun ConversationScreen(modifier: Modifier = Modifier) {
    CompanionScreen(
        eyebrow = "On mobile · conversation",
        title = "Two people, one conversation",
        lede = "Each side speaks their own language. Both sides are kept, so nobody has to remember what was said.",
        notes = listOf(
            "Turn-taking" to "Hearth opens a new turn when the other person starts speaking, so the transcript reads the way the conversation went.",
            "Kept" to "Original and translation stay beside each other. Nothing is overwritten when the next turn arrives.",
            "Face to face" to "Lay the phone flat between you and each half reads right way up.",
        ),
        callout = "This screen runs on the phone today. The desktop build shares the same pipeline, but it still needs a second audio device to tell two speakers apart.",
        modifier = modifier,
    ) { ConversationPhone() }
}
