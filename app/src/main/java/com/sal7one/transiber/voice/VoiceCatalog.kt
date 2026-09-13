package com.sal7one.transiber.voice

import com.sal7one.common_jni.voice.VoiceText

internal data class VoiceAsset(val id: String, val filename: String, val path: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/Supertone/supertonic-3/resolve/${VoiceCatalog.revision}/$path"
}
internal object VoiceCatalog {
    const val revision = "3cadd1ee6394adea1bd021217a0e650ede09a323"
    val languages = VoiceText.supertonicLanguages
    val voices = listOf("F1","F2","F3","F4","F5","M1","M2","M3","M4","M5")
    val assets = listOf(
        VoiceAsset("voice-supertonic3-duration_predictor-onnx","duration_predictor.onnx","onnx/duration_predictor.onnx",3700147,"c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        VoiceAsset("voice-supertonic3-text_encoder-onnx","text_encoder.onnx","onnx/text_encoder.onnx",36416150,"c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        VoiceAsset("voice-supertonic3-tts-json","tts.json","onnx/tts.json",8253,"42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        VoiceAsset("voice-supertonic3-unicode_indexer-json","unicode_indexer.json","onnx/unicode_indexer.json",277676,"9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
        VoiceAsset("voice-supertonic3-vector_estimator-onnx","vector_estimator.onnx","onnx/vector_estimator.onnx",256534781,"883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
        VoiceAsset("voice-supertonic3-vocoder-onnx","vocoder.onnx","onnx/vocoder.onnx",101424195,"085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
        VoiceAsset("voice-supertonic3-F1-json","F1.json","voice_styles/F1.json",292046,"bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
        VoiceAsset("voice-supertonic3-F2-json","F2.json","voice_styles/F2.json",292423,"7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
        VoiceAsset("voice-supertonic3-F3-json","F3.json","voice_styles/F3.json",290794,"12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
        VoiceAsset("voice-supertonic3-F4-json","F4.json","voice_styles/F4.json",291808,"c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
        VoiceAsset("voice-supertonic3-F5-json","F5.json","voice_styles/F5.json",291479,"45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
        VoiceAsset("voice-supertonic3-M1-json","M1.json","voice_styles/M1.json",291748,"e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
        VoiceAsset("voice-supertonic3-M2-json","M2.json","voice_styles/M2.json",292055,"b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
        VoiceAsset("voice-supertonic3-M3-json","M3.json","voice_styles/M3.json",290198,"ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
        VoiceAsset("voice-supertonic3-M4-json","M4.json","voice_styles/M4.json",291522,"ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
        VoiceAsset("voice-supertonic3-M5-json","M5.json","voice_styles/M5.json",291469,"dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"),
    )
    fun find(id: String) = assets.firstOrNull {it.id==id}
    fun file(name: String) = assets.first {it.filename==name}
    val core get() = assets.filter {it.path.startsWith("onnx/")}
}
