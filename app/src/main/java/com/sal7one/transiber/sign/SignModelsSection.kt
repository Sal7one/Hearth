package com.sal7one.transiber.sign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.SignArtifact
import com.sal7one.transiber.models.SignCatalog
import com.sal7one.transiber.models.SignModelFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Models screen "Sign" section: hand-pipeline pair status, per-language
 * classifier status, download (cloud build) or import (offline build), and
 * the persisted classifier selection. Status is honest — a language that is
 * not installed says so; the geometric ASL fallback is named as a fallback.
 */
@Composable
fun SignModelsSection(onDownloads: () -> Unit) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val downloads = remember { FileDownloads(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision) {
        // Presence checks below are cheap file reads on recomposition.
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText(UiR.string.ui_sign_language_7b123), style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        val files = remember(revision) { SignModelFiles(File(context.filesDir, SignCatalog.ROOT_DIR)) }

        // Shared hand pipeline (palm detector + landmarks), like the shared OCR detector.
        val handsReady = remember(revision) { SignCatalog.hands.all(files::installed) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Hand landmarks (MediaPipe → ONNX): " +
                    if (handsReady) "installed" else "not installed",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (ByokPolicy.FEATURE_BYOK && !handsReady) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    enqueueSignArtifacts(scope, downloads, SignCatalog.hands) { first ->
                        if (first != null) error = first
                        busy = false
                        revision++
                    }
                }) { Text("Download") }
            }
        }

        SignCatalog.languages.forEach { language ->
            val classifierInstalled = remember(revision) { files.installed(language.classifier) }
            val selected = remember(revision) { selectedClassifier(context)?.id == language.classifier.id }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(language.label, style = MaterialTheme.typography.titleMedium)
                    Text(language.description, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (classifierInstalled) "Classifier installed" else "Classifier not installed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (classifierInstalled) {
                    if (selected) {
                        OutlinedButton(enabled = false, onClick = {}) { Text("Selected") }
                    } else {
                        Button(enabled = !busy, onClick = {
                            try {
                                selectClassifier(context, language.classifier.id)
                                revision++
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            }
                        }) { Text("Use") }
                    }
                } else if (ByokPolicy.FEATURE_BYOK) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        enqueueSignArtifacts(scope, downloads, SignCatalog.hands + language.classifier) { first ->
                            if (first != null) error = first
                            busy = false
                            revision++
                        }
                    }) { Text("Download") }
                }
            }
        }

        if (!ByokPolicy.FEATURE_BYOK) {
            Text(
                "Offline build: import hand_detector.onnx, hand_landmarks_detector.onnx and a classifier " +
                    "from Models → Import. See docs/sign-language.md for the contract.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            TextButton(onClick = onDownloads) {
                Text(uiText(UiR.string.ui_view_downloads_installation_progress_f57b2))
            }
        }
    }
}

/** Enqueues one artifact per entry; reports the first real error, if any. */
private fun enqueueSignArtifacts(
    scope: CoroutineScope,
    downloads: FileDownloads,
    artifacts: List<SignArtifact>,
    onDone: (String?) -> Unit,
) {
    scope.launch {
        var firstError: String? = null
        for (artifact in artifacts) {
            try {
                withContext(Dispatchers.IO) {
                    downloads.enqueue(
                        DownloadSpec.parse(artifact.url, artifact.fileName),
                        modelPackage = false,
                        installModelId = artifact.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e.message ?: e.toString()
            }
        }
        onDone(firstError)
    }
}
