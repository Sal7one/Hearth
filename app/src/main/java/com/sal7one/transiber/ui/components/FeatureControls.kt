package com.sal7one.transiber.ui.components

import com.sal7one.transiber.ui.theme.glassPanel
import com.sal7one.transiber.ui.theme.LocalFeatureTint
import com.sal7one.transiber.ui.theme.LocalColorfulUi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One labelled action, with an optional current value. Text grows instead of truncating. */
@Composable
internal fun FeatureAction(
    title: String, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true,
) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).glassPanel(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** A visible close control and a scroll state owned by the feature, so reopening keeps position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureOptionsSheet(
    title: String, onDismiss: () -> Unit, scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        contentColor = MaterialTheme.colorScheme.onSurface,
        containerColor = if(LocalColorfulUi.current) LocalFeatureTint.current.copy(alpha=.15f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close $title") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
