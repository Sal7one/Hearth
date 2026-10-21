package com.sal7one.hearth.desktop.ui.screens.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.screens.companion.DeviceFrame
import com.sal7one.hearth.desktop.ui.screens.companion.PhoneStatus
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The phone's camera screen: a menu, OCR marks over the lines found, a translated sheet. */
@Composable
internal fun CameraPhone() {
    val colors = HearthTheme.colors
    DeviceFrame {
        PhoneStatus("18:22")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("✕", style = HearthType.small.copy(fontSize = 14.sp), color = colors.muted)
            Text("Camera", style = HearthType.label.copy(fontSize = 12.sp), color = colors.text)
            Text("⚡", style = HearthType.small.copy(fontSize = 12.sp), color = colors.muted)
        }
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(colors.surface3, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFFFAF6EC), RoundedCornerShape(4.dp))
                    .padding(14.dp),
            ) {
                Text("MITTAGSKARTE", style = HearthType.micro.copy(fontSize = 7.sp), color = androidx.compose.ui.graphics.Color(0xFF8A7B64))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tagesmenü",
                    style = HearthType.h3.copy(fontSize = 15.sp),
                    color = androidx.compose.ui.graphics.Color(0xFF2A2620),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.accent, RoundedCornerShape(2.dp))
                        .padding(2.dp),
                )
                Spacer(Modifier.height(8.dp))
                repeat(3) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFFD9D0BD), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(12.dp),
        ) {
            Text("DETECTED GERMAN → العربية", style = HearthType.micro.copy(fontSize = 8.sp), color = colors.muted)
            Spacer(Modifier.height(6.dp))
            Text(
                "قائمة الغداء: حساء البطاطس، لحم البقر المشوي، وفطيرة التفاح.",
                style = HearthType.small.copy(fontSize = 12.sp, textDirection = TextDirection.Rtl),
                color = colors.text,
            )
        }
    }
}
