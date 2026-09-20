package com.sal7one.transiber.shortcuts

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.reading.ReadingOverlayService
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.ui.theme.HearthTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visible permission owner; tiles never start capture directly from a background service. */
class OverlayLaunchActivity : AppCompatActivity() {
    private var shortcut by mutableStateOf(OverlayShortcut.CAPTIONS)
    private var phase by mutableStateOf("checking")
    private val defaultFix get() = if (shortcut == OverlayShortcut.READING) SetupFix.CAMERA else SetupFix.CAPTIONS
    private var results by mutableStateOf<List<SetupResult>>(emptyList())
    private var signature: String? = null
    private var source = CaptionSource.PLAYBACK_CAPTURE
    private var setupVisibility: AutoCloseable? = null
    private var checkJob: kotlinx.coroutines.Job? = null
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestNotificationPermission()
        else failure("Audio permission", "Audio recording permission denied. Android requires it for microphone and device audio capture.", SetupFix.AUDIO_PERMISSION)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification denial does not prohibit capture; tiles and the overlay still recover controls.
        captureConsent()
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startChecked(result.data)
        else failure("Screen sharing", "Screen sharing was cancelled. No overlay was started.", defaultFix)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupVisibility = OverlaySetupVisibility.acquire()
        shortcut = OverlayShortcut.entries.firstOrNull { it.name == (savedInstanceState?.getString("shortcut") ?: intent.getStringExtra(EXTRA)) } ?: OverlayShortcut.CAPTIONS
        phase = savedInstanceState?.getString("phase") ?: "checking"
        signature = savedInstanceState?.getString("signature")
        savedInstanceState?.getStringArrayList("results")?.chunked(4)?.filter { it.size == 4 }?.let { rows ->
            results = rows.map { SetupResult(it[0], it[1], it[2].toBoolean(), SetupFix.valueOf(it[3])) }
        }
        source = CaptionSource.entries.firstOrNull { it.name == savedInstanceState?.getString("source") } ?: source
        enableEdgeToEdge()
        setContent { HearthTheme { Diagnostics() } }
        if (phase !in setOf("audio", "notifications", "projection", "diagnostics")) checkAndStart()
    }
    override fun onStart() {
        super.onStart()
        if (setupVisibility == null) setupVisibility = OverlaySetupVisibility.acquire()
    }
    override fun onStop() {
        setupVisibility?.close(); setupVisibility = null
        super.onStop()
    }
    override fun onDestroy() {
        setupVisibility?.close(); setupVisibility = null
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Repeated tile taps cannot consume a projection token twice or stack permission prompts.
        if (phase != "diagnostics") return
        setIntent(intent)
        shortcut = OverlayShortcut.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA) } ?: shortcut
        checkAndStart()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shortcut", shortcut.name); outState.putString("phase", phase)
        outState.putString("signature", signature); outState.putString("source", source.name)
        outState.putStringArrayList("results", ArrayList(results.flatMap { listOf(it.label, it.detail, it.passed.toString(), it.fix.name) }))
        super.onSaveInstanceState(outState)
    }
    private fun failure(label: String, text: String, fix: SetupFix) {
        results = results.filterNot { it.label == label } + SetupResult(label, text, false, fix)
        phase = "diagnostics"
    }
    private fun recoverRunning(): Boolean = when {
        shortcut == OverlayShortcut.CAPTIONS && CaptionCaptureService.running.value -> { CaptionCaptureService.show(this); true }
        shortcut == OverlayShortcut.READING && ReadingOverlayService.running.value -> { ReadingOverlayService.recover(this); true }
        else -> false
    }
    private fun checkAndStart() {
        if (checkJob?.isActive == true) return
        phase = "checking"; results = emptyList()
        checkJob = lifecycleScope.launch {
            try {
                if (recoverRunning()) { finish(); return@launch }
                val checked = overlayPreflight(this@OverlayLaunchActivity, shortcut)
                results = checked.results
                if (!results.ready) { phase = "diagnostics"; return@launch }
                signature = checked.signature; source = checked.config.source
                lifecycle.withResumed {
                    if (shortcut == OverlayShortcut.CAPTIONS && ContextCompat.checkSelfPermission(this@OverlayLaunchActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        phase = "audio"; audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else requestNotificationPermission()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure("Setup check", e.message ?: e.toString(), defaultFix) }
        }
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            phase = "notifications"; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else captureConsent()
    }
    private fun captureConsent() {
        try {
            if (shortcut == OverlayShortcut.CAPTIONS && source == CaptionSource.MIC) { startChecked(null); return }
            phase = "projection"
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (shortcut == OverlayShortcut.READING && Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            projection.launch(request)
        } catch (e: Exception) { failure("Screen sharing", e.message ?: e.toString(), defaultFix) }
    }
    private fun startChecked(token: Intent?) {
        phase = "checking"
        lifecycleScope.launch {
            try {
                val checked = overlayPreflight(this@OverlayLaunchActivity, shortcut)
                results = checked.results
                if (!results.ready) { phase = "diagnostics"; return@launch }
                check(signature == checked.signature) { "Setup changed while Android permissions were open. Check again to start with the new setup." }
                lifecycle.withResumed {
                    if (shortcut == OverlayShortcut.CAPTIONS) CaptionCaptureService.start(this@OverlayLaunchActivity, source, token)
                    else ContextCompat.startForegroundService(this@OverlayLaunchActivity,
                        Intent(this@OverlayLaunchActivity, ReadingOverlayService::class.java).putExtra("projection", requireNotNull(token)))
                    finish()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure("Start overlay", e.message ?: e.toString(), defaultFix) }
        }
    }
    private fun fix(action: SetupFix) {
        when (action) {
            SetupFix.OVERLAY_PERMISSION -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            SetupFix.AUDIO_PERMISSION -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            SetupFix.SESSION -> {
                if (CaptionCaptureService.running.value || ReadingOverlayService.running.value) {
                    phase = "checking"
                    if (CaptionCaptureService.running.value) CaptionCaptureService.stop(this)
                    stopService(Intent(this, ReadingOverlayService::class.java))
                    lifecycleScope.launch {
                        repeat(50) { if (LocalWorkGate.owner.value == null && !CaptionCaptureService.running.value && !ReadingOverlayService.running.value) { checkAndStart(); return@launch }; delay(100) }
                        checkAndStart()
                    }
                } else openPage(when (LocalWorkGate.owner.value) { "Conversation" -> 7; "Camera OCR" -> 10; else -> 8 })
            }
            else -> openPage(when (action) { SetupFix.MODELS -> 1; SetupFix.TRANSLATION -> 9; SetupFix.CAMERA -> 10; else -> 0 })
        }
    }
    private fun openPage(page: Int) { startActivity(Intent(this, MainActivity::class.java).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }

    @Composable private fun Diagnostics() {
    val uiText = rememberUiText()

        var showReady by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(uiText(if (shortcut == OverlayShortcut.CAPTIONS) UiR.string.tile_captions else UiR.string.tile_reading), style = MaterialTheme.typography.headlineMedium)
                Text(if (phase == "diagnostics") uiText(UiR.string.ui_setup_diagnostics_19e28) else if (phase == "checking") uiText(UiR.string.ui_checking_your_saved_setup_36b7c) else uiText(UiR.string.ui_complete_the_android_permission_prompt_10ee5),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                if (phase == "checking") LinearProgressIndicator(Modifier.fillMaxWidth())
                if (phase == "diagnostics") {
                    Text(uiText(UiR.string.ui_nothing_was_started_fix_the_items_below_then_check_again_model_ch_5925e))
                    results.filterNot { it.passed }.forEach { result ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${if (result.passed) uiText(UiR.string.ui_ready_20c7c) else uiText(UiR.string.needs_attention)} · ${result.label}", style = MaterialTheme.typography.titleMedium)
                                Text(result.detail, color = if (result.passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                if (result.label != "Screen sharing") TextButton(onClick = { fix(result.fix) }) {
                                    Text(if (result.fix == SetupFix.SESSION && (CaptionCaptureService.running.value || ReadingOverlayService.running.value)) uiText(UiR.string.ui_stop_other_overlay_retry_1a172) else uiText(UiR.string.ui_open_settings_fd710))
                                }
                            }
                        }
                    }
                    Button(onClick = ::checkAndStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(uiText(UiR.string.ui_check_again_start_7dd2c)) }
                    val passed = results.filter { it.passed }
                    if (passed.isNotEmpty()) {
                        TextButton(onClick = { showReady = !showReady }) { Text(if (showReady) uiText(UiR.string.ui_hide_successful_checks_559b0) else uiText(UiR.string.ui_1_s_checks_passed_show_details_7e5ac, passed.size)) }
                        if (showReady) passed.forEach { Text("${it.label}: ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                TextButton(onClick = { finish() }) { Text(uiText(UiR.string.ui_cancel_77dfd)) }
            }
        }
    }
    companion object { internal const val EXTRA = "overlay_shortcut" }
}
