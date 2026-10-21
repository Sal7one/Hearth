package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.icons.HearthIcons

/**
 * The map: three tiles per row, two rows. Tiles without a desktop destination are
 * still shown — the map is the product, not only this build.
 */
@Composable
internal fun HomeFeatureMap(
    features: List<HomeFeature>,
    onOpen: (DesktopScreen) -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        HomeSectionHeader(
            eyebrow = HomeCopy.MAP_EYEBROW,
            title = HomeCopy.MAP_TITLE,
            lede = HomeCopy.MAP_LEDE,
            action = {
                HearthButton(
                    text = HomeCopy.OPEN_WORKSPACE,
                    onClick = onOpenWorkspace,
                    tone = ButtonTone.Ghost,
                    size = ButtonSize.Small,
                    icon = HearthIcons.ArrowRight,
                    iconAfter = true,
                )
            },
        )
        Spacer(Modifier.height(22.dp))

        features.chunked(3).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { feature ->
                    FeatureTile(
                        feature = feature,
                        onClick = feature.destination?.let { destination -> { onOpen(destination) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
