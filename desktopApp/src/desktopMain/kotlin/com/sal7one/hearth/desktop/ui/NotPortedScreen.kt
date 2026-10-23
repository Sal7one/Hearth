package com.sal7one.hearth.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.Eyebrow
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import androidx.compose.material3.Text

/**
 * Temporary destination for screens that are still being rebuilt. It says so
 * plainly instead of rendering a convincing-looking shell with no behaviour.
 */
@Composable
internal fun NotPortedScreen(screen: DesktopScreen, onBack: () -> Unit) {
    val colors = HearthTheme.colors
    PageScaffold {
        Eyebrow("In progress")
        Spacer(Modifier.height(8.dp))
        Text(screen.title, style = HearthType.h1, color = colors.text)
        Spacer(Modifier.height(18.dp))
        Banner(
            text = "This screen is being rebuilt against the approved design. The controllers behind it are unchanged and still work.",
            tone = BannerTone.Neutral,
        )
        Spacer(Modifier.height(22.dp))
        Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HearthButton("Back to Home", onBack, tone = ButtonTone.Primary)
        }
    }
}
