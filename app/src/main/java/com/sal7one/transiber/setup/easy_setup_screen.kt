package com.sal7one.transiber.setup

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.ui.theme.glassPanel

@Composable internal fun EasySetupScreen(step: EasySetupStep, onStep: (EasySetupStep) -> Unit, onCaptions: () -> Unit, onHome: () -> Unit, onSettings: () -> Unit, onDownloads: () -> Unit) {
    val uiText = rememberUiText()

    key(step) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(step.finished) {
            Text(uiText(UiR.string.ui_setup_saved_8f224),style=MaterialTheme.typography.headlineLarge)
            Text(if(step.local) uiText(UiR.string.ui_nemotron_translation_ready_on_this_phone_7864d) else uiText(UiR.string.ui_your_speech_connection_is_saved_it_connects_when_you_start_389c0))
            Button(onClick=onCaptions,modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text(uiText(UiR.string.ui_open_live_captions_d696b))}
            OutlinedButton(onClick=onHome,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text(uiText(UiR.string.ui_explore_hearth_19205))}
        } else if(step==EasySetupStep.CHOICE) {
            Text(uiText(UiR.string.ui_how_would_you_like_to_start_f8dd2),style=MaterialTheme.typography.headlineLarge)
            Text(uiText(UiR.string.ui_easy_setup_for_live_captions_caac2),style=MaterialTheme.typography.titleMedium)
            SetupChoice(uiText(UiR.string.ui_on_this_phone_03f49),uiText(UiR.string.ui_nemotron_local_translation_fd186),R.drawable.home_captions,Color(0xFF568DDC),true) {onStep(EasySetupStep.LOCAL)}
            SetupChoice(uiText(UiR.string.ui_cloud_b2efe),if(ByokPolicy.FEATURE_BYOK) uiText(UiR.string.ui_openai_openrouter_local_server_6a1cc) else uiText(UiR.string.ui_unavailable_in_this_offline_build_89320),R.drawable.home_text,Color(0xFFB183EA),ByokPolicy.FEATURE_BYOK) {onStep(EasySetupStep.CLOUD)}
        } else {
            TextButton(onClick={onStep(EasySetupStep.CHOICE)}) {Text(uiText(UiR.string.ui_choose_another_setup_fbf26))}
            if(step.local) EasySetupLocalUi(onDone={onStep(step.complete())},onDownloads=onDownloads)
            else if(ByokPolicy.FEATURE_BYOK) EasySetupCloudUi(onDone={onStep(step.complete())})
        }
        Text(uiText(UiR.string.ui_easy_setup_uses_a_few_defaults_full_setup_is_in_settings_f7349),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=onSettings) {Text(uiText(UiR.string.ui_open_full_settings_12aaa))}
    }
    }
}

@Composable private fun SetupChoice(title: String, description: String, art: Int, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().glassPanel(tint),
        colors=CardDefaults.cardColors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface)) {
        Image(painterResource(art),null,Modifier.fillMaxWidth().height(85.dp),contentScale=ContentScale.Crop)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,style=MaterialTheme.typography.headlineMedium)
            Text(description,style=MaterialTheme.typography.bodyMedium)
        }
    }
}
