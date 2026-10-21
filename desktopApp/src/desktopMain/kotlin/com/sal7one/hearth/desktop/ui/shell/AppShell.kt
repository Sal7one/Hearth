package com.sal7one.hearth.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * The window frame: a top bar and the current screen. Screens own their scrolling;
 * the shell never clips them.
 */
@Composable
internal fun AppShell(
    current: DesktopScreen,
    status: String,
    statusLive: Boolean,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    onNavigate: (DesktopScreen) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(HearthTheme.colors.bg)) {
        TopBar(
            current = current,
            status = status,
            statusLive = statusLive,
            dark = dark,
            onToggleTheme = onToggleTheme,
            onNavigate = onNavigate,
        )
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Centred, scrollable page body at the design's 1240 dp maximum width.
 * `bottom` leaves room for anything pinned over the content (the call bar).
 */
@Composable
internal fun PageScaffold(
    modifier: Modifier = Modifier,
    bottom: Int = 96,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier
                .widthIn(max = Space.maxWidth)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = Space.pageX, vertical = 0.dp)
                .padding(top = Space.pageTop, bottom = bottom.dp),
            content = content,
        )
    }
}

/** A narrower page used by settings-style screens that are forms, not dashboards. */
@Composable
internal fun CompactPageScaffold(
    modifier: Modifier = Modifier,
    bottom: Int = 96,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier
                .widthIn(max = 980.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = Space.pageTop, bottom = bottom.dp)
                .padding(horizontal = Space.pageX),
            content = content,
        )
    }
}
