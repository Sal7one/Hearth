package com.sal7one.hearth.desktop.ui.screens.presets

/**
 * The presets Hearth ships with. They reference real catalog ids; if a catalog id
 * disappears the preset reports it as missing rather than silently picking
 * something else.
 */
internal object BuiltInPresets {
    val all: List<PresetDefinition> = listOf(
        PresetDefinition(
            id = "live-captions",
            title = "Live captions",
            description = "Subtitle anything without translating it. The lightest way to run Hearth for an hour.",
            speechId = "whisper-large-v3-turbo-q5_0",
            featured = true,
        ),
        PresetDefinition(
            id = "arabic-meetings",
            title = "Arabic meetings",
            description = "Speak Arabic, keep English notes — or the other way round. The voice reads the translation back.",
            speechId = "whisper-large-v3-turbo-q5_0",
            translationId = "eurollm-17b-q4",
            voiceId = "supertonic-3",
            voiceStyle = "F2",
            featured = true,
        ),
        PresetDefinition(
            id = "long-session",
            title = "Long session",
            description = "Smaller speech model, lighter on battery. Good for lectures and calls over an hour.",
            speechId = "whisper-small-q5_1",
            translationId = "eurollm-17b-q4",
        ),
        PresetDefinition(
            id = "interviews",
            title = "Interviews",
            description = "The strongest translation model, with a voice for playback. Heavy, but the most careful.",
            speechId = "whisper-medium-q5_0",
            translationId = "riva-translate-4b-v2-q4",
            voiceId = "supertonic-3",
            voiceStyle = "M1",
        ),
    )
}
