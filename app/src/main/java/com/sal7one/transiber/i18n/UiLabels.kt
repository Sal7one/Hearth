package com.sal7one.transiber.i18n

import com.sal7one.transiber.R

// Explicit UI adapters: persisted IDs and engine/provider metadata stay locale-independent.
internal fun UiText.title(value: com.sal7one.transiber.home.HomeService): String = this(when (value) {
    com.sal7one.transiber.home.HomeService.CAPTIONS -> R.string.label_homeservice_captions_title
    com.sal7one.transiber.home.HomeService.CONVERSATION -> R.string.label_homeservice_conversation_title
    com.sal7one.transiber.home.HomeService.FACE -> R.string.label_homeservice_face_title
    com.sal7one.transiber.home.HomeService.TEXT -> R.string.label_homeservice_text_title
    com.sal7one.transiber.home.HomeService.CAMERA -> R.string.label_homeservice_camera_title
    com.sal7one.transiber.home.HomeService.SCREEN -> R.string.label_homeservice_screen_title
})

internal fun UiText.description(value: com.sal7one.transiber.home.HomeService): String = this(when (value) {
    com.sal7one.transiber.home.HomeService.CAPTIONS -> R.string.label_homeservice_captions_description
    com.sal7one.transiber.home.HomeService.CONVERSATION -> R.string.label_homeservice_conversation_description
    com.sal7one.transiber.home.HomeService.FACE -> R.string.label_homeservice_face_description
    com.sal7one.transiber.home.HomeService.TEXT -> R.string.label_homeservice_text_description
    com.sal7one.transiber.home.HomeService.CAMERA -> R.string.label_homeservice_camera_description
    com.sal7one.transiber.home.HomeService.SCREEN -> R.string.label_homeservice_screen_description
})

internal fun UiText.action(value: com.sal7one.transiber.home.HomeService): String = this(when (value) {
    com.sal7one.transiber.home.HomeService.CAPTIONS -> R.string.label_homeservice_captions_action
    com.sal7one.transiber.home.HomeService.CONVERSATION -> R.string.label_homeservice_conversation_action
    com.sal7one.transiber.home.HomeService.FACE -> R.string.label_homeservice_face_action
    com.sal7one.transiber.home.HomeService.TEXT -> R.string.label_homeservice_text_action
    com.sal7one.transiber.home.HomeService.CAMERA -> R.string.label_homeservice_camera_action
    com.sal7one.transiber.home.HomeService.SCREEN -> R.string.label_homeservice_screen_action
})

internal fun UiText.label(value: com.sal7one.transiber.ui.MainTab): String = this(when (value) {
    com.sal7one.transiber.ui.MainTab.CAPTIONS -> R.string.label_maintab_captions_label
    com.sal7one.transiber.ui.MainTab.TALK -> R.string.label_maintab_talk_label
    com.sal7one.transiber.ui.MainTab.TRANSLATE -> R.string.label_maintab_translate_label
    com.sal7one.transiber.ui.MainTab.CAMERA -> R.string.label_maintab_camera_label
    com.sal7one.transiber.ui.MainTab.SETTINGS -> R.string.label_maintab_settings_label
})

internal fun UiText.title(value: com.sal7one.transiber.ui.MainTab): String = this(when (value) {
    com.sal7one.transiber.ui.MainTab.CAPTIONS -> R.string.label_maintab_captions_title
    com.sal7one.transiber.ui.MainTab.TALK -> R.string.label_maintab_talk_title
    com.sal7one.transiber.ui.MainTab.TRANSLATE -> R.string.label_maintab_translate_title
    com.sal7one.transiber.ui.MainTab.CAMERA -> R.string.label_maintab_camera_title
    com.sal7one.transiber.ui.MainTab.SETTINGS -> R.string.label_maintab_settings_title
})

internal fun UiText.label(value: com.sal7one.transiber.ui.theme.ThemeMode): String = this(when (value) {
    com.sal7one.transiber.ui.theme.ThemeMode.SYSTEM -> R.string.label_thememode_system_label
    com.sal7one.transiber.ui.theme.ThemeMode.LIGHT -> R.string.label_thememode_light_label
    com.sal7one.transiber.ui.theme.ThemeMode.DARK -> R.string.label_thememode_dark_label
})

internal fun UiText.label(value: com.sal7one.transiber.ui.theme.AccentPreset): String = this(when (value) {
    com.sal7one.transiber.ui.theme.AccentPreset.CLEAN -> R.string.label_accentpreset_clean_label
    com.sal7one.transiber.ui.theme.AccentPreset.INK -> R.string.label_accentpreset_ink_label
    com.sal7one.transiber.ui.theme.AccentPreset.SKY -> R.string.label_accentpreset_sky_label
    com.sal7one.transiber.ui.theme.AccentPreset.OCEAN -> R.string.label_accentpreset_ocean_label
    com.sal7one.transiber.ui.theme.AccentPreset.EMBER -> R.string.label_accentpreset_ember_label
    com.sal7one.transiber.ui.theme.AccentPreset.FOREST -> R.string.label_accentpreset_forest_label
    com.sal7one.transiber.ui.theme.AccentPreset.AMETHYST -> R.string.label_accentpreset_amethyst_label
})

internal fun UiText.description(value: com.sal7one.transiber.ui.theme.AccentPreset): String = this(when (value) {
    com.sal7one.transiber.ui.theme.AccentPreset.CLEAN -> R.string.label_accentpreset_clean_description
    com.sal7one.transiber.ui.theme.AccentPreset.INK -> R.string.label_accentpreset_ink_description
    com.sal7one.transiber.ui.theme.AccentPreset.SKY -> R.string.label_accentpreset_sky_description
    com.sal7one.transiber.ui.theme.AccentPreset.OCEAN -> R.string.label_accentpreset_ocean_description
    com.sal7one.transiber.ui.theme.AccentPreset.EMBER -> R.string.label_accentpreset_ember_description
    com.sal7one.transiber.ui.theme.AccentPreset.FOREST -> R.string.label_accentpreset_forest_description
    com.sal7one.transiber.ui.theme.AccentPreset.AMETHYST -> R.string.label_accentpreset_amethyst_description
})

internal fun UiText.label(value: com.sal7one.transiber.ui.theme.NavigationLayout): String = this(when (value) {
    com.sal7one.transiber.ui.theme.NavigationLayout.SIMPLE -> R.string.label_navigationlayout_simple_label
    com.sal7one.transiber.ui.theme.NavigationLayout.TABS -> R.string.label_navigationlayout_tabs_label
})

internal fun UiText.label(value: com.sal7one.transiber.settings.SettingsLocation): String = this(when (value) {
    com.sal7one.transiber.settings.SettingsLocation.LOCAL -> R.string.label_settingslocation_local_label
    com.sal7one.transiber.settings.SettingsLocation.CLOUD -> R.string.label_settingslocation_cloud_label
})

internal fun UiText.label(value: com.sal7one.transiber.settings.SettingsFeature): String = this(when (value) {
    com.sal7one.transiber.settings.SettingsFeature.SPEECH -> R.string.label_settingsfeature_speech_label
    com.sal7one.transiber.settings.SettingsFeature.TRANSLATION -> R.string.label_settingsfeature_translation_label
    com.sal7one.transiber.settings.SettingsFeature.VOICES -> R.string.label_settingsfeature_voices_label
    com.sal7one.transiber.settings.SettingsFeature.CAMERA -> R.string.label_settingsfeature_camera_label
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionMode): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionMode.CAPTIONS -> R.string.label_captionmode_captions_label
    com.sal7one.transiber.caption.CaptionMode.TRANSLATE -> R.string.label_captionmode_translate_label
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionSource): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionSource.PLAYBACK_CAPTURE -> R.string.label_captionsource_playback_capture_label
    com.sal7one.transiber.caption.CaptionSource.MIC -> R.string.label_captionsource_mic_label
})

internal fun UiText.explanation(value: com.sal7one.transiber.caption.CaptionSource): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionSource.PLAYBACK_CAPTURE -> R.string.label_captionsource_playback_capture_explanation
    com.sal7one.transiber.caption.CaptionSource.MIC -> R.string.label_captionsource_mic_explanation
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionEngineChoice): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionEngineChoice.VOSK -> R.string.label_captionenginechoice_vosk_label
    com.sal7one.transiber.caption.CaptionEngineChoice.WHISPER -> R.string.label_captionenginechoice_whisper_label
    com.sal7one.transiber.caption.CaptionEngineChoice.MOONSHINE -> R.string.label_captionenginechoice_moonshine_label
    com.sal7one.transiber.caption.CaptionEngineChoice.QWEN -> R.string.label_captionenginechoice_qwen_label
    com.sal7one.transiber.caption.CaptionEngineChoice.NEMOTRON -> R.string.label_captionenginechoice_nemotron_label
    com.sal7one.transiber.caption.CaptionEngineChoice.CLOUD -> R.string.label_captionenginechoice_cloud_label
})

internal fun UiText.explanation(value: com.sal7one.transiber.caption.CaptionEngineChoice): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionEngineChoice.VOSK -> R.string.label_captionenginechoice_vosk_explanation
    com.sal7one.transiber.caption.CaptionEngineChoice.WHISPER -> R.string.label_captionenginechoice_whisper_explanation
    com.sal7one.transiber.caption.CaptionEngineChoice.MOONSHINE -> R.string.label_captionenginechoice_moonshine_explanation
    com.sal7one.transiber.caption.CaptionEngineChoice.QWEN -> R.string.label_captionenginechoice_qwen_explanation
    com.sal7one.transiber.caption.CaptionEngineChoice.NEMOTRON -> R.string.label_captionenginechoice_nemotron_explanation
    com.sal7one.transiber.caption.CaptionEngineChoice.CLOUD -> R.string.label_captionenginechoice_cloud_explanation
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionTheme): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionTheme.DARK -> R.string.label_captiontheme_dark_label
    com.sal7one.transiber.caption.CaptionTheme.HIGH_CONTRAST -> R.string.label_captiontheme_high_contrast_label
    com.sal7one.transiber.caption.CaptionTheme.LIGHT -> R.string.label_captiontheme_light_label
    com.sal7one.transiber.caption.CaptionTheme.SUBTLE -> R.string.label_captiontheme_subtle_label
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionSpeakerChoice): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionSpeakerChoice.SYSTEM -> R.string.label_captionspeakerchoice_system_label
    com.sal7one.transiber.caption.CaptionSpeakerChoice.CUSTOM -> R.string.label_captionspeakerchoice_custom_label
    com.sal7one.transiber.caption.CaptionSpeakerChoice.NATIVE -> R.string.label_captionspeakerchoice_native_label
    com.sal7one.transiber.caption.CaptionSpeakerChoice.SHARED -> R.string.label_captionspeakerchoice_shared_label
    com.sal7one.transiber.caption.CaptionSpeakerChoice.CLOUD -> R.string.label_captionspeakerchoice_cloud_label
})

internal fun UiText.explanation(value: com.sal7one.transiber.caption.CaptionSpeakerChoice): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionSpeakerChoice.SYSTEM -> R.string.label_captionspeakerchoice_system_explanation
    com.sal7one.transiber.caption.CaptionSpeakerChoice.CUSTOM -> R.string.label_captionspeakerchoice_custom_explanation
    com.sal7one.transiber.caption.CaptionSpeakerChoice.NATIVE -> R.string.label_captionspeakerchoice_native_explanation
    com.sal7one.transiber.caption.CaptionSpeakerChoice.SHARED -> R.string.label_captionspeakerchoice_shared_explanation
    com.sal7one.transiber.caption.CaptionSpeakerChoice.CLOUD -> R.string.label_captionspeakerchoice_cloud_explanation
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionAnchor): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionAnchor.TOP -> R.string.label_captionanchor_top_label
    com.sal7one.transiber.caption.CaptionAnchor.CENTER -> R.string.label_captionanchor_center_label
    com.sal7one.transiber.caption.CaptionAnchor.BOTTOM -> R.string.label_captionanchor_bottom_label
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionFontScale): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionFontScale.SMALL -> R.string.label_captionfontscale_small_label
    com.sal7one.transiber.caption.CaptionFontScale.NORMAL -> R.string.label_captionfontscale_normal_label
    com.sal7one.transiber.caption.CaptionFontScale.LARGE -> R.string.label_captionfontscale_large_label
    com.sal7one.transiber.caption.CaptionFontScale.HUGE -> R.string.label_captionfontscale_huge_label
})

internal fun UiText.label(value: com.sal7one.transiber.caption.CaptionDisplay): String = this(when (value) {
    com.sal7one.transiber.caption.CaptionDisplay.ORIGINAL -> R.string.label_captiondisplay_original_label
    com.sal7one.transiber.caption.CaptionDisplay.TRANSLATED -> R.string.label_captiondisplay_translated_label
    com.sal7one.transiber.caption.CaptionDisplay.BOTH -> R.string.label_captiondisplay_both_label
})

internal fun UiText.label(value: com.sal7one.transiber.byok.CloudConfigStore.SttMode): String = this(when (value) {
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.BATCH -> R.string.label_sttmode_batch_label
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_SONIOX -> R.string.label_sttmode_streaming_soniox_label
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_ELEVENLABS -> R.string.label_sttmode_streaming_elevenlabs_label
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_DEEPGRAM -> R.string.label_sttmode_streaming_deepgram_label
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_OPENAI -> R.string.label_sttmode_streaming_openai_label
    com.sal7one.transiber.byok.CloudConfigStore.SttMode.STREAMING_ASSEMBLYAI -> R.string.label_sttmode_streaming_assemblyai_label
})

internal fun UiText.label(value: com.sal7one.transiber.byok.CloudConfigStore.Provider): String = this(when (value) {
    com.sal7one.transiber.byok.CloudConfigStore.Provider.OPENAI -> R.string.label_provider_openai_label
    com.sal7one.transiber.byok.CloudConfigStore.Provider.OPENROUTER -> R.string.label_provider_openrouter_label
    com.sal7one.transiber.byok.CloudConfigStore.Provider.CUSTOM -> R.string.label_provider_custom_label
})

internal fun UiText.label(value: com.sal7one.transiber.byok.SpeechModelCatalog.Role): String = this(when (value) {
    com.sal7one.transiber.byok.SpeechModelCatalog.Role.TRANSCRIPTION -> R.string.label_role_transcription_label
    com.sal7one.transiber.byok.SpeechModelCatalog.Role.LIVE -> R.string.label_role_live_label
    com.sal7one.transiber.byok.SpeechModelCatalog.Role.TRANSLATION -> R.string.label_role_translation_label
    com.sal7one.transiber.byok.SpeechModelCatalog.Role.DIARIZATION -> R.string.label_role_diarization_label
})

internal fun UiText.label(value: com.sal7one.transiber.ui.components.CapabilityStatus): String = this(when (value) {
    com.sal7one.transiber.ui.components.CapabilityStatus.READY -> R.string.label_capabilitystatus_ready_label
    com.sal7one.transiber.ui.components.CapabilityStatus.SETUP_NEEDED -> R.string.label_capabilitystatus_setup_needed_label
    com.sal7one.transiber.ui.components.CapabilityStatus.PARTLY_READY -> R.string.label_capabilitystatus_partly_ready_label
    com.sal7one.transiber.ui.components.CapabilityStatus.UNAVAILABLE -> R.string.label_capabilitystatus_unavailable_label
})

internal fun UiText.modelSection(id: String): String = when (id) {
    "Speech" -> this(R.string.ui_speech_d00d8)
    "Translation" -> this(R.string.label_settingsfeature_translation_label)
    "Camera" -> this(R.string.label_maintab_camera_label)
    "Voices" -> this(R.string.ui_voices_40273)
    else -> id
}

internal fun UiText.languageName(code: String): String = when (code) {
    "auto" -> this(R.string.language_auto)
    "model" -> this(R.string.language_model)
    else -> java.util.Locale.forLanguageTag(code).getDisplayName(locale)
}

internal fun UiText.languageLabel(option: com.sal7one.common_jni.language.LanguageOption): String {
    val translated = languageName(option.code)
    return if (option.code in setOf("auto", "model") || translated == option.nativeName) translated
        else "${option.nativeName} · $translated"
}

internal fun UiText.label(profile: com.sal7one.transiber.ocr.OcrProfile): String = when(profile.id) {
    "latin" -> this(R.string.ocr_latin)
    "cjk" -> this(R.string.ocr_cjk)
    "arabic" -> this(R.string.ocr_arabic)
    "cyrillic" -> this(R.string.ocr_cyrillic)
    "manga" -> this(R.string.ocr_manga)
    "meiki" -> this(R.string.ocr_meiki)
    else -> profile.label
}
internal fun UiText.description(profile: com.sal7one.transiber.ocr.OcrProfile): String = when(profile.id) {
    "manga" -> this(R.string.ocr_manga_details)
    "meiki" -> this(R.string.ocr_meiki_details)
    "latin", "cjk", "arabic", "cyrillic" -> this(R.string.ocr_printed)
    else -> profile.description
}
internal fun UiText.installation(source: com.sal7one.transiber.models.ModelSource): String = when(source.id) {
    "nemotron-3.5-asr-0.6b" -> this(R.string.install_nemotron)
    "qwen3-asr-0.6b" -> this(R.string.install_qwen)
    "qwen3-asr-1.7b" -> this(R.string.install_qwen_large)
    "moonshine-tiny-en-v2", "moonshine-base-en-v2" -> this(R.string.install_moonshine)
    "whisper" -> this(R.string.install_whisper)
    "vosk" -> this(R.string.install_vosk)
    "marian-en-ar" -> this(R.string.install_marian)
    else -> source.installation
}
internal fun UiText.label(preset: com.sal7one.transiber.caption.CaptionPreset): String = when(preset.id) {
    "bottom_strip" -> this(R.string.preset_bottom)
    "top_ticker" -> this(R.string.preset_top)
    "center_focus" -> this(R.string.preset_center)
    "theater" -> this(R.string.preset_theater)
    "reading" -> this(R.string.preset_reading)
    "subtle_chip" -> this(R.string.preset_subtle)
    else -> preset.label
}

internal fun UiText.label(status: com.sal7one.transiber.conversation.ConversationStatus): String = this(when(status) {
    com.sal7one.transiber.conversation.ConversationStatus.READY -> R.string.phase_ready
    com.sal7one.transiber.conversation.ConversationStatus.PREPARING -> R.string.phase_preparing
    com.sal7one.transiber.conversation.ConversationStatus.LISTENING -> R.string.phase_listening
    com.sal7one.transiber.conversation.ConversationStatus.FINISHING -> R.string.phase_finishing
    com.sal7one.transiber.conversation.ConversationStatus.TRANSLATING -> R.string.phase_translating
    com.sal7one.transiber.conversation.ConversationStatus.SPEAKING -> R.string.phase_speaking
})
