package com.sal7one.transiber.setup

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
    key(step) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(step.finished) {
            Text("Setup saved",style=MaterialTheme.typography.headlineLarge)
            Text(if(step.local) "Nemotron + translation. Ready on this phone." else "Your speech connection is saved. It connects when you start.")
            Button(onClick=onCaptions,modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)) {Text("Open live captions")}
            OutlinedButton(onClick=onHome,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Explore Hearth")}
        } else if(step==EasySetupStep.CHOICE) {
            Text("How would you like\nto start?",style=MaterialTheme.typography.headlineLarge)
            Text("Easy setup for live captions.",style=MaterialTheme.typography.titleMedium)
            SetupChoice("On this phone","Nemotron + local translation",R.drawable.home_captions,Color(0xFF568DDC),true) {onStep(EasySetupStep.LOCAL)}
            SetupChoice("Cloud",if(ByokPolicy.FEATURE_BYOK) "OpenAI · OpenRouter · Local server" else "Unavailable in this offline build",R.drawable.home_text,Color(0xFFB183EA),ByokPolicy.FEATURE_BYOK) {onStep(EasySetupStep.CLOUD)}
        } else {
            TextButton(onClick={onStep(EasySetupStep.CHOICE)}) {Text("Choose another setup")}
            if(step.local) EasySetupLocalUi(onDone={onStep(step.complete())},onDownloads=onDownloads)
            else if(ByokPolicy.FEATURE_BYOK) EasySetupCloudUi(onDone={onStep(step.complete())})
        }
        Text("Easy setup uses a few defaults. Full setup is in Settings.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=onSettings) {Text("Open full Settings ↗")}
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
