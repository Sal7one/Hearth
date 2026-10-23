package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Elapsed time since the session actually became active. It resets when the
 * session ends, so the number on screen is never a leftover from a previous run.
 */
@Composable
internal fun rememberSessionClock(active: Boolean): String {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(1_000)
        }
    }
    return formatCaptionTime(elapsedMs)
}
