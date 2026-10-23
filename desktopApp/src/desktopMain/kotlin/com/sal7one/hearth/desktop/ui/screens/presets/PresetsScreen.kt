package com.sal7one.hearth.desktop.ui.screens.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.screens.models.ModelsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold

/**
 * Presets: four bundles over catalog models, each stating readiness and cost
 * before it can be applied. Applying is all-or-nothing by design.
 */
@Composable
internal fun PresetsScreen(
    state: PresetsState,
    actions: PresetsActions,
    current: DesktopScreen = DesktopScreen.PRESETS,
    modifier: Modifier = Modifier,
) {
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = PresetsCopy.EYEBROW,
            title = PresetsCopy.TITLE,
            lede = PresetsCopy.LEDE,
            actions = {
                HearthButton(
                    text = "Open library",
                    onClick = { actions.onNavigate(DesktopScreen.MODELS) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                    icon = HearthIcons.ArrowRight,
                    iconAfter = true,
                )
            },
        )

        ModelsSubnav(current = current, onNavigate = actions.onNavigate)

        state.presets.chunked(2).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        onApply = { actions.onApply(preset.id) },
                        onSetUp = { actions.onOpenDownloads() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(16.dp))
        BuildPresetCard(onOpenLibrary = { actions.onNavigate(DesktopScreen.MODELS) })

        Spacer(Modifier.height(16.dp))
        Banner(text = PresetsCopy.FOOTNOTE, tone = BannerTone.Neutral)
    }
}
