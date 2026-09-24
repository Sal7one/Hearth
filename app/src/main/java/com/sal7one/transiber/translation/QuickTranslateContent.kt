package com.sal7one.transiber.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.i18n.rememberUiText

/** Compact translator shown over the app that supplied selected/shared text. */
@Composable
internal fun QuickTranslateContent(
    selectedText: String,
    canReplace: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onReplace: (String) -> Unit,
    onOpenFull: () -> Unit,
) {
    val context = LocalContext.current
    val uiText = rememberUiText()
    val prefs = remember(context) { context.getSharedPreferences("typed-translation", 0) }
    var source by remember { mutableStateOf(prefs.getString("source", "en") ?: "en") }
    var target by remember { mutableStateOf(prefs.getString("target", "ar") ?: "ar") }
    var picking by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    val config by remember(context) { CaptionConfigStore.config(context) }
        .collectAsState(initial = CaptionOverlayConfig())
    val connectionRevision by ConversationTranslationSettings.revision.collectAsState()
    val localModelId = remember(connectionRevision, config.localTranslationModelId) {
        ConversationTranslationSettings.localModel(context, config.localTranslationModelId)
    }
    val label = remember(connectionRevision, localModelId) {
        ConversationTranslationSettings.label(context, localModelId)
    }
    val languages = remember(connectionRevision, localModelId) {
        ConversationTranslationSettings.languages(context, localModelId)
    }
    val sourceChoices = remember(connectionRevision, localModelId, languages) {
        languages.filterTo(mutableSetOf()) { from ->
            languages.any { to -> ConversationTranslationSettings.supports(context, localModelId, from, to) }
        }
    }
    val targetChoices = remember(connectionRevision, localModelId, languages, source) {
        languages.filterTo(mutableSetOf()) { to ->
            ConversationTranslationSettings.supports(context, localModelId, source, to)
        }
    }
    val controller = remember(context) { TypedTranslationController(context) }
    val state by controller.state.collectAsState()

    DisposableEffect(lifecycle, controller) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopped = true
                controller.pause()
            } else if (event == Lifecycle.Event.ON_START && stopped) {
                stopped = false
                resumed++
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    LaunchedEffect(selectedText, source, target, connectionRevision, localModelId, retry, resumed) {
        prefs.edit().putString("source", source).putString("target", target).apply()
        try {
            controller.update(
                selectedText,
                source,
                target,
                ConversationTranslationSettings.snapshot(context, localModelId),
                immediate = true,
            )
        } catch (error: Exception) {
            controller.error(error)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(max = 620.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiText(R.string.action_hearth_translate), style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, uiText(R.string.ui_close_bbfa7))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = "source" }, modifier = Modifier.weight(1f),
                    enabled = sourceChoices.isNotEmpty()) {
                    Text(LanguageCatalog.option(source).nativeName, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = uiText(R.string.ui_source_language_c951f) + ": " +
                                LanguageCatalog.option(source).label
                        })
                }
                IconButton(onClick = { val old = source; source = target; target = old }) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, uiText(R.string.ui_swap_languages_efa6c))
                }
                OutlinedButton(onClick = { picking = "target" }, modifier = Modifier.weight(1f),
                    enabled = targetChoices.isNotEmpty()) {
                    Text(LanguageCatalog.option(target).nativeName, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = uiText(R.string.ui_translate_to_a1ba6) + ": " +
                                LanguageCatalog.option(target).label
                        })
                }
            }
            Text(uiText(R.string.ui_original_c0a80), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(selectedText, modifier = Modifier.heightIn(max = 130.dp)
                    .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Text(uiText(R.string.ui_translation_ac26a), style = MaterialTheme.typography.labelLarge)
            if (state.busy) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 22.dp))
                Text(uiText(R.string.ui_translating_ae47b))
            }
            if (state.output.isNotBlank()) SelectionContainer {
                Text(state.output, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { retry++ }) { Text(uiText(R.string.ui_retry_9f5cd)) }
                    TextButton(onClick = onOpenFull) {
                        Text(uiText(R.string.action_hearth_translate_open_full))
                    }
                }
            }
            if (state.output.isNotBlank()) Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onCopy(state.output) }) { Text(uiText(R.string.ui_copy_af74f)) }
                if (canReplace) OutlinedButton(onClick = { onReplace(state.output) }) {
                    Text(uiText(R.string.action_hearth_translate_replace))
                }
            }
        }
    }

    picking?.let { which ->
        QuickLanguageDialog(
            title = uiText(if (which == "source") R.string.ui_source_language_c951f
                else R.string.ui_translate_to_a1ba6),
            selected = if (which == "source") source else target,
            codes = if (which == "source") sourceChoices else targetChoices,
            onSelect = { code ->
                if (which == "source") source = code else target = code
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun QuickLanguageDialog(
    title: String,
    selected: String,
    codes: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiText = rememberUiText()
    var query by remember { mutableStateOf("") }
    val choices = remember(codes, query) { LanguageCatalog.choices(codes, query) }
    val initialIndex = remember(codes, selected) {
        LanguageCatalog.choices(codes).indexOfFirst { it.code == selected }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(query) { if (query.isNotBlank()) listState.scrollToItem(0) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                TextField(query, { query = it },
                    label = { Text(uiText(R.string.ui_search_languages_ea93e)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp), state = listState) {
                    items(choices, key = { it.code }) { option ->
                        TextButton(onClick = { onSelect(option.code) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("${option.flag}  ${option.label}", Modifier.weight(1f))
                            if (option.code == selected) Text("✓")
                        }
                    }
                }
            }
        }
    }
}
