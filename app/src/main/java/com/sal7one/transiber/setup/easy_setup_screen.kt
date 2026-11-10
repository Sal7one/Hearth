package com.sal7one.transiber.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.preferences.SetupMode
import com.sal7one.transiber.preferences.rememberSetupMode

@Composable
internal fun EasySetupScreen(
    step: EasySetupStep,
    entry: EasySetupStep,
    onStep: (EasySetupStep) -> Unit,
    onCaptions: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onModels: (Boolean) -> Unit,
) {
    val text = rememberUiText()
    val context = LocalContext.current
    val television = com.sal7one.transiber.caption.TvControls.isTvDevice(context)
    val forms = rememberSaveableStateHolder()
    forms.SaveableStateProvider(step.name) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                step.finished -> {
                    Text(text(R.string.ui_setup_saved_8f224), style = MaterialTheme.typography.headlineLarge)
                    Text(text(if (step.local) R.string.setup_bundle_complete else
                        R.string.ui_your_speech_connection_is_saved_it_connects_when_you_start_389c0))
                    if (television) com.sal7one.transiber.caption.RemoteButton(text(R.string.tv_step_overlay), onClick = {
                        com.sal7one.transiber.caption.OverlayControlsActivity.open(context)
                    })
                    Button(onClick = onCaptions, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(text(R.string.ui_open_live_captions_d696b))
                    }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text(R.string.ui_explore_hearth_19205))
                    }
                }
                step.local -> {
                    val presets = step == EasySetupStep.PRESETS
                    EasySetupLocalUi(onDone = { onStep(step.complete()) }, onDownloads = onDownloads,
                        presets = presets, onBrowseModels = onModels)
                    if (presets) {
                        HorizontalDivider()
                        if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = { onStep(EasySetupStep.CLOUD) }) {
                            Text(text(R.string.setup_preset_cloud))
                        }
                        if (rememberSetupMode() == SetupMode.ADVANCED) TextButton(onClick = { onStep(EasySetupStep.LOCAL) }) {
                            Text(text(R.string.setup_preset_manual))
                        }
                    }
                }
                step == EasySetupStep.CLOUD && ByokPolicy.FEATURE_BYOK -> {
                    TextButton(onClick = { onStep(entry) }) { Text(text(R.string.ui_choose_another_setup_fbf26)) }
                    EasySetupCloudUi(onDone = { onStep(step.complete()) })
                }
                else -> {
                    Text(text(R.string.ui_how_would_you_like_to_start_f8dd2), style = MaterialTheme.typography.headlineLarge)
                    if (television) com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
                    else OutlinedButton(onClick = { onStep(EasySetupStep.LOCAL) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text(R.string.ui_on_this_phone_03f49))
                    }
                    if (ByokPolicy.FEATURE_BYOK) Button(onClick = { onStep(EasySetupStep.CLOUD) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text(R.string.setup_preset_cloud))
                    }
                }
            }
            if (!step.finished) TextButton(onClick = onSettings) { Text(text(R.string.ui_open_full_settings_12aaa)) }
        }
    }
}
