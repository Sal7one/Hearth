package com.sal7one.hearth.desktop.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.HearthIconButton
import com.sal7one.hearth.desktop.ui.components.StatusPill
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * 66 px bar: brand, six destinations, status, theme and settings.
 * A section's pages keep their section highlighted, so the gear lights up for
 * every settings screen and Models stays lit for presets and downloads.
 */
@Composable
internal fun TopBar(
    current: DesktopScreen,
    status: String,
    statusLive: Boolean,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    onNavigate: (DesktopScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val activeSection = current.section
    Row(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(colors.bg.copy(alpha = 0.94f))
            .border(0.dp, colors.border)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(
            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onNavigate(DesktopScreen.HOME) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painterResource("hearth.png"),
                contentDescription = "Hearth",
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)),
            )
            Text(
                "Hearth",
                style = HearthType.h3.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DesktopScreen.topLevel.forEach { screen ->
                val active = screen == current || (activeSection != null && screen.section == activeSection)
                NavPill(screen.navLabel ?: screen.title, active) { onNavigate(screen) }
            }
        }

        Spacer(Modifier.weight(1f))

        StatusPill(status, statusLive)
        HearthIconButton(
            icon = if (dark) HearthIcons.Sun else HearthIcons.Moon,
            label = if (dark) "Switch to light appearance" else "Switch to dark appearance",
            onClick = onToggleTheme,
        )
        HearthIconButton(
            icon = HearthIcons.Gear,
            label = "Settings",
            onClick = { onNavigate(DesktopScreen.APPEARANCE) },
            tint = if (activeSection == com.sal7one.hearth.desktop.ui.DesktopSection.SETTINGS) colors.text else null,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun NavPill(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .height(34.dp)
            .background(
                when {
                    active -> colors.raised
                    hovered -> colors.surface2
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(999.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = HearthType.label,
            color = if (active || hovered) colors.text else colors.muted,
            maxLines = 1,
        )
    }
}

/** Shared icon wrapper so the shell and pages cannot drift on sizing. */
@Composable
internal fun ShellIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int = 17) {
    Icon(icon, null, Modifier.size(size.dp), tint = HearthTheme.colors.muted)
}

@Composable
internal fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
internal fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))
