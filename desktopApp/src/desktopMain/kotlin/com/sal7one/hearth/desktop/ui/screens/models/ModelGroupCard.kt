package com.sal7one.hearth.desktop.ui.screens.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * One stage group. A model that changed on disk keeps its place in the list with a
 * visible error instead of disappearing, because a missing row is not a diagnosis.
 */
@Composable
internal fun ModelGroupCard(
    group: ModelGroup,
    onAction: (String) -> Unit,
    onImport: (String) -> Unit,
    importEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.title, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(group.detail, style = HearthType.small, color = colors.muted)
            }
            GroupCountChip(group.installedCount)
        }
        ModelsDivider()
        if (group.rows.isEmpty()) {
            Text(
                ModelsCopy.EMPTY,
                style = HearthType.small,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                group.rows.forEachIndexed { index, row ->
                    if (index > 0) ModelsDivider()
                    ModelRowView(row, onAction = { onAction(row.id) }, onImport = { onImport(row.id) }, importEnabled = importEnabled)
                }
            }
        }
    }
}
