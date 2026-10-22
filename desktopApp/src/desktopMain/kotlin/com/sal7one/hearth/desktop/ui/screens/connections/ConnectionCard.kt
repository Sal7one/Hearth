package com.sal7one.hearth.desktop.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthTextField
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * One cloud engine. Until it is the saved route for its stage it stays read-only:
 * editing an endpoint that nothing uses would just look like it took effect.
 */
@Composable
internal fun ConnectionCard(
    row: ConnectionRow,
    actions: ConnectionsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    var endpoint by remember(row.engineId, row.selected) { mutableStateOf(row.endpoint) }
    var model by remember(row.engineId, row.selected) { mutableStateOf(row.model) }
    var credentialId by remember(row.engineId, row.selected) { mutableStateOf(row.credentialId) }
    var key by remember(row.engineId) { mutableStateOf("") }

    HearthCard(modifier, padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.name, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Chip(row.stageLabel, tone = ChipTone.Neutral)
                    Chip(
                        if (row.selected) ConnectionsCopy.IN_USE else ConnectionsCopy.NOT_IN_USE,
                        tone = if (row.selected) ChipTone.Accent else ChipTone.Neutral,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    row.unavailableReason ?: if (row.selected) row.endpoint else row.endpoint,
                    style = HearthType.monoSmall,
                    color = if (row.available) colors.muted else colors.err,
                    maxLines = 1,
                )
            }
            if (!row.selected) {
                HearthButton(
                    ConnectionsCopy.USE,
                    onClick = { actions.onActivate(row.engineId) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                    enabled = row.available,
                )
            }
        }

        if (row.selected) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                FormRow(label = ConnectionsCopy.ENDPOINT, hint = ConnectionsCopy.ENDPOINT_HINT) {
                    HearthTextField(endpoint, { endpoint = it }, "https://api.example.com/v1")
                }
                FormRow(label = ConnectionsCopy.MODEL, hint = ConnectionsCopy.MODEL_HINT) {
                    HearthTextField(model, { model = it }, "model-name")
                }
                FormRow(label = ConnectionsCopy.CREDENTIAL, hint = ConnectionsCopy.CREDENTIAL_HINT) {
                    HearthTextField(credentialId, { credentialId = it }, "openai-main or env:OPENAI_API_KEY")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HearthButton(
                            ConnectionsCopy.SAVE,
                            onClick = { actions.onSave(row.engineId, endpoint.trim(), model.trim(), credentialId.trim()) },
                            tone = ButtonTone.Primary,
                            size = ButtonSize.Small,
                        )
                        if (row.hasCredential) {
                            Chip(ConnectionsCopy.KEY_SAVED, tone = ChipTone.Ok)
                        }
                    }
                }
                FormRow(label = ConnectionsCopy.KEY, hint = ConnectionsCopy.KEY_HINT, showDivider = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HearthTextField(
                            value = key,
                            onValueChange = { key = it },
                            placeholder = "sk-…",
                            modifier = Modifier.weight(1f),
                            secret = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        HearthButton(
                            ConnectionsCopy.SAVE_KEY,
                            onClick = {
                                val id = credentialId.trim().ifBlank { "${row.stage.name.lowercase()}-${row.engineId}" }
                                actions.onSaveKey(id, key)
                                key = ""
                            },
                            tone = ButtonTone.Secondary,
                            size = ButtonSize.Small,
                            enabled = key.isNotBlank(),
                        )
                        Spacer(Modifier.width(8.dp))
                        HearthButton(
                            ConnectionsCopy.FORGET_KEY,
                            onClick = { actions.onForgetKey(credentialId.trim()) },
                            tone = ButtonTone.Danger,
                            size = ButtonSize.Small,
                            enabled = row.hasCredential,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
