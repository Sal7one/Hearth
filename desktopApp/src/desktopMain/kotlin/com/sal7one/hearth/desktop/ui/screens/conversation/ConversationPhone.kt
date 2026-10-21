package com.sal7one.hearth.desktop.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.screens.companion.DeviceFrame
import com.sal7one.hearth.desktop.ui.screens.companion.PhoneStatus
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The phone's conversation screen: turn-taking with both languages kept. */
@Composable
internal fun ConversationPhone() {
    val colors = HearthTheme.colors
    DeviceFrame {
        PhoneStatus("21:04")
        Spacer(Modifier.height(10.dp))
        Text("Conversation", style = HearthType.h3.copy(fontSize = 19.sp), color = colors.text)
        Text("English ↔ العربية", style = HearthType.small.copy(fontSize = 11.sp), color = colors.muted)
        Spacer(Modifier.height(14.dp))

        Bubble("You · English", "Do you have a table for two, near the window?", "هل لديكم طاولة لشخصين قرب النافذة؟", me = true)
        Spacer(Modifier.height(10.dp))
        Bubble("Them · العربية", "نعم، طاولة قرب النافذة. تفضّل.", "Yes — a table by the window. Please.", me = false, rtl = true)
        Spacer(Modifier.height(10.dp))
        Bubble("You · English", "Perfect. Thank you.", "ممتاز. شكراً لك.", me = true)

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(46.dp).background(colors.accent, RoundedCornerShape(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Listening", style = HearthType.label.copy(fontSize = 12.sp), color = colors.onAccent)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Bubble(label: String, text: String, translation: String, me: Boolean, rtl: Boolean = false) {
    val colors = HearthTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = HearthType.micro.copy(fontSize = 8.sp), color = colors.muted)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .align(if (me) Alignment.End else Alignment.Start)
                .background(if (me) colors.raised else colors.surface2, RoundedCornerShape(14.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    text,
                    style = HearthType.small.copy(
                        fontSize = 12.sp,
                        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
                    ),
                    color = colors.text,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    translation,
                    style = HearthType.small.copy(
                        fontSize = 11.sp,
                        textDirection = if (rtl) TextDirection.Ltr else TextDirection.Rtl,
                    ),
                    color = colors.muted,
                )
            }
        }
    }
}
