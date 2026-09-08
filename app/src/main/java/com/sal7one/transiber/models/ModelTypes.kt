package com.sal7one.transiber.models

import android.content.Context
import android.net.Uri
import com.sal7one.common_jni.model.ModelDigest
import com.sal7one.common_jni.model.ModelDigestTrust
import com.sal7one.transiber.ui.state.ScreenState
import com.sal7one.transiber.ui.state.stateInScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class ModelEngineType(val displayName: String) {
    WHISPER("Whisper"),
    VOSK("Vosk"),
    ONNX("ONNX"),
    TRANSLATE("Translation"),
}

data class LoadedModel(
    val id: String,
    val name: String,
    val engineType: ModelEngineType,
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isValid: Boolean,
    val digest: ModelDigest? = null,
    val validationMessage: String? = null,
)

