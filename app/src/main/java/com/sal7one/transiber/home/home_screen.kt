package com.sal7one.transiber.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Feature destinations only: opening Home never allocates models or starts capture. */
@Composable
internal fun HomeScreen(
    onCaptions: () -> Unit,
    onTalk: (Boolean) -> Unit,
    onTranslate: () -> Unit,
    onCamera: () -> Unit,
    onReading: () -> Unit,
    onSettings: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("What would you like to do?", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
        }
        item { FeatureBubble("Live captions", "Read or translate speech from videos and nearby voices", Icons.Default.ClosedCaption, onCaptions) }
        item { FeatureBubble("Conversation", "Speak, translate and keep both sides of the conversation", Icons.Default.Forum) { onTalk(false) } }
        item { FeatureBubble("Face to face", "Two people, two languages, one split screen", Icons.Default.People) { onTalk(true) } }
        item { FeatureBubble("Type to translate", "Write or paste text, then read it aloud", Icons.Default.Translate, onTranslate) }
        item { FeatureBubble("Camera & photos", "Translate signs, documents and images", Icons.Default.CameraAlt, onCamera) }
        item { FeatureBubble("Screen & manga", "Translate text over your reader or another app", Icons.Default.AutoStories, onReading) }
        item { TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Settings, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Models, cloud & app settings")
        } }
    }
}

@Composable
private fun FeatureBubble(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
