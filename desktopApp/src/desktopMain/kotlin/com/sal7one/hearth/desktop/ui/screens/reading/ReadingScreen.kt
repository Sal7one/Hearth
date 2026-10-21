package com.sal7one.hearth.desktop.ui.screens.reading

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sal7one.hearth.desktop.ui.screens.companion.CompanionScreen

/** Screen reading is a phone feature; the desktop build does not capture the display. */
@Composable
internal fun ReadingScreen(modifier: Modifier = Modifier) {
    CompanionScreen(
        eyebrow = "On mobile · screen & manga",
        title = "Read with translation on the page",
        lede = "Books, comics, another app. Hearth reads the page as it is and lays the translation where the original sits.",
        notes = listOf(
            "Nothing re-typeset" to "Panels, gutters and page numbers stay where they were. Only the words change hands.",
            "Trigger" to "Translate one bubble on demand, or keep the whole page live while you read.",
        ),
        callout = "Screen reading needs Android’s screen-capture consent. The desktop build does not capture your display yet.",
        modifier = modifier,
    ) { ReadingPhone() }
}
