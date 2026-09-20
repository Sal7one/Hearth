package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.ui.theme.AccentPreset
import com.sal7one.transiber.ui.theme.AppDesign
import com.sal7one.transiber.ui.theme.FFmpegStudioTheme
import com.sal7one.transiber.ui.theme.ThemeMode

/**
 * Entry point for live captions. Orchestrates the two special permissions in
 * the honest order:
 * 1. SYSTEM_ALERT_WINDOW (overlay) — must be granted in system settings.
 * 2. MediaProjection consent — required for device-audio capture; the
 *    microphone source skips it entirely.
 *
 * Starts [CaptionCaptureService] and finishes; the overlay takes over from
 * there.
 */
class CaptionStartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CaptionStartRoot(requestedSource = intent.getStringExtra("chosen_source")?.let { runCatching { CaptionSource.valueOf(it) }.getOrNull() }, onDone = { finish() })
        }
    }

    companion object {
        fun start(context: Context, source: CaptionSource? = null) {
            context.startActivity(
                Intent(context, CaptionStartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("chosen_source", source?.name),
            )
        }
    }
}

@Composable
private fun CaptionStartRoot(requestedSource: CaptionSource?, onDone: () -> Unit) {
    val context = LocalContext.current
    val themeMode = com.sal7one.transiber.ui.theme.rememberThemeMode()
    val accentPreset = com.sal7one.transiber.ui.theme.rememberAccentPreset()

    FFmpegStudioTheme(
        themeMode = themeMode,
        accentPreset = accentPreset,
    ) {
        CaptionStartSystemBars(themeMode)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            CaptionStartScreen(requestedSource, onDone = onDone)
        }
    }
}

@Composable
private fun CaptionStartSystemBars(themeMode: ThemeMode) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
private fun CaptionStartScreen(requestedSource: CaptionSource?, onDone: () -> Unit) {
    val uiText = rememberUiText()

    val context = LocalContext.current
    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var source by remember { mutableStateOf(requestedSource ?: CaptionSource.PLAYBACK_CAPTURE) }
    var micPermissionDenied by remember { mutableStateOf(false) }
    var notificationsDenied by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) overlayGranted = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptionCaptureService.start(context, CaptionSource.PLAYBACK_CAPTURE, result.data)
            onDone()
        }
    }
    val launchCapture: () -> Unit = {
        if (source == CaptionSource.PLAYBACK_CAPTURE) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            CaptionCaptureService.start(context, CaptionSource.MIC, null)
            onDone()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted
        // Capture remains legal after denial; the screen explains how to restore controls.
        if (granted) launchCapture()
    }
    val requestNotifications: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else launchCapture()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionDenied = !granted
        if (granted) requestNotifications()
    }
    val requestCapture: () -> Unit = {
        // Playback capture ALSO requires RECORD_AUDIO, even though it does not use the mic.
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else requestNotifications()
    }

    var attempted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(overlayGranted) {
        if (requestedSource != null && overlayGranted && !attempted) {
            attempted = true
            requestCapture()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(AppDesign.Dimens.SpacingLg),
        verticalArrangement = Arrangement.spacedBy(AppDesign.Dimens.SpacingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(uiText(UiR.string.ui_live_captions_83fd1), style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (requestedSource == null) uiText(UiR.string.ui_choose_an_audio_source_to_start_81909) else uiText(UiR.string.ui_1_s_complete_android_permissions_to_start_e9e4e, uiText.label(source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDone) {
                Icon(Icons.Default.Close, uiText(UiR.string.ui_close_bbfa7))
            }
        }

        if (!overlayGranted) {
            PermissionCard(onGrant = {
                // Opening system settings pauses this activity; re-check on resume.
                overlayGranted = false
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                context.startActivity(intent)
            })
        } else {
            if (requestedSource == null) SourceCard(
                selected = source,
                onSelect = { source = it },
            )

            when (source) {
                CaptionSource.PLAYBACK_CAPTURE -> Button(
                    onClick = requestCapture,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.VolumeUp, null)
                    Spacer(Modifier.height(0.dp))
                    Text(uiText(UiR.string.ui_start_captions_from_device_audio_9e84c))
                }

                CaptionSource.MIC -> Button(
                    onClick = requestCapture,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, null)
                    Text(uiText(UiR.string.ui_start_captions_from_microphone_41530))
                }
            }

            if (notificationsDenied) {
                Text(uiText(UiR.string.ui_notifications_are_disabled_enable_them_for_pause_stop_and_bubble_57b9d),
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(uiText(UiR.string.ui_enable_caption_notification_controls_4594b)) }
                TextButton(onClick = launchCapture) { Text(uiText(UiR.string.ui_continue_with_bubble_controls_only_0ddfd)) }
            }
            if (micPermissionDenied) {
                Text(
                    uiText(UiR.string.ui_audio_recording_permission_denied_android_requires_it_for_both_mi_af57b),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        runCatching {
                            val activity = context as? android.app.Activity
                            activity?.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                ) { Text(uiText(UiR.string.ui_open_settings_fd710)) }
            }

            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(uiText(UiR.string.ui_cancel_77dfd))
            }

            if (requestedSource == null) Text(uiText(UiR.string.ui_some_apps_block_device_audio_choose_microphone_if_capture_is_sile_acb86), style = MaterialTheme.typography.bodySmall)

        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    val uiText = rememberUiText()

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppDesign.Dimens.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                uiText(UiR.string.ui_allow_display_over_other_apps_b1453),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                uiText(UiR.string.ui_live_captions_draws_a_floating_caption_bubble_on_top_of_the_app_ec64b) +
                    uiText(UiR.string.ui_you_are_watching_android_requires_a_one_time_setting_for_this_2aea3),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGrant) { Text(uiText(UiR.string.ui_open_settings_fd710)) }
            Text(
                uiText(UiR.string.ui_return_here_after_enabling_it_29b55),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(
    selected: CaptionSource,
    onSelect: (CaptionSource) -> Unit,
) {
    val uiText = rememberUiText()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(uiText(UiR.string.ui_audio_source_a1ee0), style = MaterialTheme.typography.titleMedium)
        CaptionSource.entries.forEach { source ->
            val isSelected = selected == source
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(AppDesign.Dimens.RadiusMd),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(source) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (source == CaptionSource.MIC) Icons.Default.Mic else Icons.Default.VolumeUp,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    ) {
                        Text(uiText.label(source), style = MaterialTheme.typography.titleSmall)
                        Text(
                            uiText.explanation(source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
