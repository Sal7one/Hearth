package com.sal7one.hearth.desktop.ui.screens.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.screens.companion.DeviceFrame
import com.sal7one.hearth.desktop.ui.screens.companion.PhoneStatus
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The phone's reader: a page kept in place with translations sitting over it. */
@Composable
internal fun ReadingPhone() {
    val colors = HearthTheme.colors
    DeviceFrame {
        PhoneStatus("23:41")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = HearthType.small.copy(fontSize = 14.sp), color = colors.muted)
            Spacer(Modifier.width(6.dp))
            Text("Reading", style = HearthType.label.copy(fontSize = 12.sp), color = colors.text)
            Spacer(Modifier.weight(1f))
            Text("日本語 → العربية", style = HearthType.monoSmall.copy(fontSize = 9.sp), color = colors.muted)
        }
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(Color(0xFFF3EFE6), RoundedCornerShape(10.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Panel(Modifier.weight(1.1f), "لن أعود إلى هناك مرة أخرى.")
                Panel(Modifier.weight(0.9f), null)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Panel(Modifier.weight(1f), "…لكنك قلت ذلك في المرة الماضية.")
                Panel(Modifier.weight(1f), "هذه المرة مختلفة.")
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.background(colors.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
            ) { Text("Live", style = HearthType.micro.copy(fontSize = 8.sp), color = colors.accent) }
            Box(
                Modifier.background(colors.surface2, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
            ) { Text("Tap to translate", style = HearthType.micro.copy(fontSize = 8.sp), color = colors.muted) }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier, translation: String?) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xFFD8D3C8), RoundedCornerShape(3.dp))
            .padding(7.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            Text(
                translation ?: "",
                style = HearthType.small.copy(fontSize = 10.sp, textDirection = TextDirection.Rtl),
                color = Color(0xFF1D1A17),
            )
        }
    }
}
