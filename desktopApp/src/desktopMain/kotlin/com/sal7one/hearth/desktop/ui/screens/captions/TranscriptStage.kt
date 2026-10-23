package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthShapes

/**
 * The transcript surface. It owns no state: the clock is passed in, the lines are
 * passed in, and the only side effect is scrolling to the newest line when one
 * arrives.
 */
@Composable
internal fun TranscriptStage(
    state: CaptionsState,
    clock: String,
    onFinishMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val shapes = HearthShapes()
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Column(
        modifier
            .clip(shapes.card)
            .background(colors.surface)
            .border(1.dp, colors.border, shapes.card),
    ) {
        StageStatusBar(state, clock, onFinishMicrophone)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        if (state.lines.isEmpty()) {
            EmptyTranscript(Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
            ) {
                items(state.lines, key = { it.id }) { line ->
                    TranscriptLine(
                        line = line,
                        rtl = state.targetRtl,
                        translationEnabled = state.translationEnabled,
                        showOriginal = state.showOriginal,
                        showPendingOriginal = state.showPendingOriginal,
                        translationFailed = state.error != null && line.translation.isNullOrBlank() && !line.partial,
                    )
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }
}
