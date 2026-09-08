package com.sal7one.transiber.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.ui.theme.AppDesign
import com.sal7one.transiber.ui.theme.HearthTheme
import java.util.Locale

enum class CapabilityStatus(val label: String) {
    READY("Ready"),
    SETUP_NEEDED("Setup needed"),
    PARTLY_READY("Partly ready"),
    UNAVAILABLE("Unavailable"),
}

@Composable
fun CapabilityTag(
    status: CapabilityStatus,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = when (status) {
        CapabilityStatus.READY -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        CapabilityStatus.SETUP_NEEDED -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        CapabilityStatus.PARTLY_READY -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
        CapabilityStatus.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(AppDesign.Dimens.RadiusFull),
        border = if (status == CapabilityStatus.PARTLY_READY) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Text(
            text = buildString {
                append(status.label)
                if (!detail.isNullOrBlank()) append(" · ").append(detail)
            },
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
fun HearthCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(AppDesign.Dimens.RadiusLg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppDesign.Dimens.ElevationSm),
    ) {
        Column(
            modifier = Modifier.padding(AppDesign.Dimens.SpacingMd),
            content = content,
        )
    }
}

