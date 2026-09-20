package com.sal7one.transiber.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Three actions, no directory copy. Only the chosen feature opens its setup. */
@Composable
internal fun HomeScreen(
    onCaptions: () -> Unit,
    onTalk: (Boolean) -> Unit,
    onTranslate: () -> Unit,
    onCamera: () -> Unit,
    onReading: () -> Unit,
) {
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Scroll at large text/small heights; never shrink or clip the touch targets.
        val buttonHeight = ((maxHeight - 64.dp) / 3).coerceIn(150.dp, 230.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            HomeVisualButton("Listen", HomeArt.LISTEN, "Listen: live captions and audio translation", buttonHeight, onCaptions)
            HomeVisualButton("Talk", HomeArt.TALK, "Talk: conversation or face to face", buttonHeight) { chooser = "talk" }
            HomeVisualButton("Translate", HomeArt.TRANSLATE, "Translate: text, camera or screen", buttonHeight) { chooser = "translate" }
        }
    }
    chooser?.let { selected ->
        HomeModeSheet(selected, onDismiss = { chooser = null },
            onTalk = { face -> chooser = null; onTalk(face) },
            onTranslate = { chooser = null; onTranslate() },
            onCamera = { chooser = null; onCamera() },
            onReading = { chooser = null; onReading() })
    }
}

@Composable
internal fun HomeVisualButton(title: String, art: HomeArt, description: String,
    height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Card(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = height).semantics { contentDescription = description },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            HomeFeatureArt(art, Modifier.weight(1.2f).height(height - 24.dp))
        }
    }
}
