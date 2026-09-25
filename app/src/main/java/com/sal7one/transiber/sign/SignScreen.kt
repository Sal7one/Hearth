package com.sal7one.transiber.sign

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.runtime.LocalWorkGate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * In-app sign-language page: honest setup status, the fingerspelling
 * language picker, overlay start/stop, and a plain statement of what the
 * feature can and cannot do. Live letter output belongs to the overlay.
 */
@Composable
internal fun SignScreen(onModels: () -> Unit) {
    val uiText = rememberUiText()
    val context = LocalContext.current

    var revision by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-check every permission/file fact when returning from Settings or Models.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val running by SignOverlayService.running.collectAsStateWithLifecycle()
    val busyOwner by LocalWorkGate.owner.collectAsStateWithLifecycle()

    val facts = remember(revision) {
        val installed = availableClassifiers(context)
        val chosen = selectedClassifier(context) ?: installed.firstOrNull()
        SignSetupFacts(
            overlayGranted = Settings.canDrawOverlays(context),
            cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            handsInstalled = handModelDir(context) != null,
            // One complete classifier per language, by SignLanguage id ("asl"/"arsl").
            classifiers = SignLanguage.entries.associate { entry ->
                entry.id to installed.firstOrNull { it.language == entry }
            },
            selectedId = chosen?.id,
        )
    }
    val anyClassifier = facts.classifiers.values.any { it != null }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (running) {
            StatusLine(ok = true, uiText(UiR.string.ui_sign_overlay_is_running_6ee79))
            OutlinedButton(onClick = { SignOverlayService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_stop_overlay_67e13))
            }
        }

        if (!facts.overlayGranted) {
            SetupCard(
                title = uiText(UiR.string.ui_allow_display_over_other_apps_b1453),
                body = uiText(UiR.string.ui_sign_language_draws_a_floating_letter_bubble_on_top_of_the_app_yo_00afa),
                button = uiText(UiR.string.ui_open_settings_fd710),
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }
            Text(uiText(UiR.string.ui_return_here_after_enabling_it_29b55),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (facts.overlayGranted && !facts.cameraGranted) {
            SetupCard(
                title = uiText(UiR.string.ui_allow_camera_1dfb1),
                body = uiText(UiR.string.ui_android_requires_camera_access_to_read_fingerspelling_from_the_ca_3e33c),
                button = uiText(UiR.string.ui_allow_camera_1dfb1),
            ) { cameraLauncher.launch(Manifest.permission.CAMERA) }
        }

        StatusLine(ok = facts.handsInstalled, text = if (facts.handsInstalled) {
            uiText(UiR.string.ui_hand_models_installed_17d26)
        } else {
            uiText(UiR.string.ui_hand_models_are_missing_open_models_to_download_or_import_them_33e3d)
        })
        if (!facts.handsInstalled) {
            OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_open_models_53f82))
            }
        }

        Text(uiText(UiR.string.ui_fingerspelling_language_c8f50), style = MaterialTheme.typography.titleMedium)
        SignLanguage.entries.forEach { entry ->
            val classifier = facts.classifiers[entry.id]
            val selected = classifier != null && classifier.id == facts.selectedId
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = classifier != null) {
                        classifier?.let {
                            selectClassifier(context, it.id)
                            revision++
                        }
                    },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(entry.capability, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Classifier names are domain data; the status line stays honest per language.
                    Text(
                        if (classifier != null) {
                            uiText(UiR.string.ui_1_s_classifier_installed_11bd8, entry.displayName)
                        } else {
                            uiText(UiR.string.ui_1_s_classifier_not_installed_5e06f, entry.displayName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (classifier != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        if (!running) {
            if (busyOwner != null) {
                Text(uiText(UiR.string.ui_stop_1_s_before_starting_sign_language_bbf83, busyOwner ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            val canStart = facts.overlayGranted && facts.cameraGranted && facts.handsInstalled && busyOwner == null
            Button(
                onClick = { SignStartActivity.start(context) },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(uiText(UiR.string.ui_start_fingerspelling_overlay_170e8))
            }
            if (!anyClassifier) {
                Text(uiText(UiR.string.ui_without_the_trained_classifier_only_a_geometric_subset_of_asl_let_84210),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText(UiR.string.ui_what_this_does_0da9d), style = MaterialTheme.typography.titleMedium)
                Text(uiText(UiR.string.ui_hearth_watches_the_front_camera_and_types_the_letters_it_reads_ov_9e5ec),
                    style = MaterialTheme.typography.bodySmall)
                Text(uiText(UiR.string.ui_static_letters_only_motion_letters_such_as_j_and_z_in_asl_are_not_1d76b),
                    style = MaterialTheme.typography.bodySmall)
                Text(uiText(UiR.string.ui_details_and_model_sources_docs_sign_language_md_a6d81),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Mutable snapshot of every external fact the page reports; recomputed on resume. */
private data class SignSetupFacts(
    val overlayGranted: Boolean,
    val cameraGranted: Boolean,
    val handsInstalled: Boolean,
    /** One complete installed classifier per language id, or null when missing. */
    val classifiers: Map<String, SignClassifierInfo?>,
    val selectedId: String?,
)

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (ok) Color(0xFF00795C) else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupCard(title: String, body: String, button: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(button) }
        }
    }
}
