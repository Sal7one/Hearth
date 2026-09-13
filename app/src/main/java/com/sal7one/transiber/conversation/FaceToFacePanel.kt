package com.sal7one.transiber.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.common_jni.language.LanguageCatalog

/** A second presentation of the same conversation. Owns no capture, inference or saved state. */
@Composable
internal fun FaceToFacePanel(
    state: ConversationState,
    textSize: Float,
    showOriginal: Boolean,
    canSpeak: (Int) -> Boolean,
    onSpeak: (Int) -> Unit,
    onFinish: () -> Unit,
    onLanguage: (Int) -> Unit,
    onHistory: (Int) -> Unit,
    onOptions: () -> Unit,
    onCancel: () -> Unit,
    onSwap: () -> Unit,
) {
    // On short landscape screens and with large system fonts the entire split remains
    // scrollable, instead of compressing either person's Speak/Finish control away.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight.coerceAtLeast(600.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp, vertical = 8.dp)) {
                PersonFace(
                    side = 1, state = state, textSize = textSize, showOriginal = showOriginal,
                    canSpeak = canSpeak(1), onSpeak = { onSpeak(1) }, onFinish = onFinish,
                    onLanguage = { onLanguage(1) }, onHistory = { onHistory(1) },
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).fillMaxWidth().rotate(180f),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOptions, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Tune, "Face-to-face options")
                    }
                    Text(state.status, Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    }, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    if (state.busy) IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Cancel current turn")
                    } else IconButton(onClick = onSwap, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SwapVert, "Swap my language and their language")
                    }
                }
                PersonFace(
                    side = 0, state = state, textSize = textSize, showOriginal = showOriginal,
                    canSpeak = canSpeak(0), onSpeak = { onSpeak(0) }, onFinish = onFinish,
                    onLanguage = { onLanguage(0) }, onHistory = { onHistory(0) },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PersonFace(
    side: Int,
    state: ConversationState,
    textSize: Float,
    showOriginal: Boolean,
    canSpeak: Boolean,
    onSpeak: () -> Unit,
    onFinish: () -> Unit,
    onLanguage: () -> Unit,
    onHistory: () -> Unit,
    container: Color,
    foreground: Color,
    modifier: Modifier,
) {
    val code = if (side == 0) state.session.first else state.session.second
    val language = LanguageCatalog.option(code)
    val person = if (side == 0) "Me" else "Them"
    // Never relabel old messages after swapping languages. A source is shown only
    // to its original speaker; incoming text is shown only in its actual target.
    val turn = state.session.turns.lastOrNull {
        (it.speaker == side && it.source == code) || (it.speaker != side && it.target == code)
    }
    val ownTurn = turn?.speaker == side
    val liveTurn = state.busy && turn?.id == state.session.turns.lastOrNull()?.id
    val finishing = state.listening && state.activeSpeaker == side
    val mainText = if (ownTurn) turn?.original.orEmpty() else turn?.translation.orEmpty()
    val partial = state.partial.takeIf { liveTurn && ownTurn }.orEmpty()
    val scroll = rememberScrollState()
    // A new message opens at its beginning; partial revisions do not yank the reader.
    LaunchedEffect(turn?.id) { scroll.scrollTo(0) }
    val size = textSize.coerceIn(18f, 40f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onLanguage, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "$person, ${language.englishName}, change language"
            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("$person · ${language.nativeName}", style = MaterialTheme.typography.titleMedium)
        }
        Surface(color = container, contentColor = foreground, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(
                role = Role.Button, onClickLabel = "Open ${language.englishName} conversation history", onClick = onHistory,
            )) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (turn == null) {
                    FaceText("Speak, or tap here to read your conversation", code, size, foreground)
                } else {
                    Text(if (ownTurn) "You said" else "Translation", style = MaterialTheme.typography.labelMedium)
                    if (mainText.isNotBlank()) FaceText(mainText, code, size, foreground)
                    if (partial.isNotBlank()) {
                        Text("Live speech", style = MaterialTheme.typography.labelSmall)
                        FaceText(partial, code, size, foreground)
                    }
                    if (!ownTurn && turn.translation.isBlank()) Text(when (turn.status) {
                        TurnStatus.LISTENING -> "Waiting for the other person to finish"
                        TurnStatus.TRANSLATING -> "Translating…"
                        TurnStatus.ERROR, TurnStatus.INTERRUPTED -> "Translation unavailable"
                        TurnStatus.COMPLETE -> "No translated text is available"
                    }, style = MaterialTheme.typography.bodyLarge)
                    if (ownTurn && mainText.isBlank() && partial.isBlank() && liveTurn)
                        Text(state.status, style = MaterialTheme.typography.bodyLarge)
                    if (showOriginal && !ownTurn && turn.original.isNotBlank()) {
                        HorizontalDivider(color = foreground.copy(alpha = 0.2f))
                        Text("Original · ${LanguageCatalog.option(turn.source).nativeName}", style = MaterialTheme.typography.labelMedium)
                        FaceText(turn.original, turn.source, (size - 3f).coerceAtLeast(16f), foreground)
                    }
                    turn.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                if (side == 0 && state.error != null && state.error != turn?.error)
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                if (!canSpeak && !state.busy) Text("Speech is unavailable for this direction. Check the languages and models in Options.", style = MaterialTheme.typography.bodySmall)
                Text("Tap for ${language.nativeName} history", style = MaterialTheme.typography.labelSmall)
            }
        }
        Button(onClick = if (finishing) onFinish else onSpeak,
            enabled = finishing || (!state.busy && canSpeak),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                contentDescription = "${if (finishing) "Finish" else "Speak"} ${language.englishName}, $person"
            }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(if (finishing) Icons.Default.Stop else Icons.Default.Mic, null)
            Text(if (finishing) "Finish" else "Speak ${language.nativeName}", Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FaceText(text: String, code: String, size: Float, color: Color) {
    val rtl = LanguageCatalog.option(code).rtl
    Text(text, Modifier.fillMaxWidth(), color = color,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp,
            textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
            textAlign = if (rtl) TextAlign.Right else TextAlign.Left))
}
