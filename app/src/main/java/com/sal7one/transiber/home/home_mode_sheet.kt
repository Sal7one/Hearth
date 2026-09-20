package com.sal7one.transiber.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeModeSheet(mode: String, onDismiss: () -> Unit,
    onTalk: (Boolean) -> Unit, onTranslate: () -> Unit, onCamera: () -> Unit, onReading: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode == "talk") {
                HomeVisualButton("Chat", HomeArt.TALK, "Open conversation", 140.dp) { onTalk(false) }
                HomeVisualButton("Face to face", HomeArt.FACE, "Open face to face", 140.dp) { onTalk(true) }
            } else {
                HomeVisualButton("Text", HomeArt.TYPE, "Type to translate", 140.dp, onTranslate)
                HomeVisualButton("Camera", HomeArt.CAMERA, "Translate camera or photos", 140.dp, onCamera)
                HomeVisualButton("Screen", HomeArt.SCREEN, "Translate screen, manga or books", 140.dp, onReading)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
