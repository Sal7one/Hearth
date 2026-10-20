package com.sal7one.transiber.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One disclosure replaces separate source, license, precision and technical buttons. */
@Composable
internal fun ModelLibraryDisclosure(label: String, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text(label, Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
