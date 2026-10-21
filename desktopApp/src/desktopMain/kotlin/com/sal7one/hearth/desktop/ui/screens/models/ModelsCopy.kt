package com.sal7one.hearth.desktop.ui.screens.models

import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.DesktopScreen

/** Copy for the library. Technical facts are allowed here — this is the setup screen. */
internal object ModelsCopy {
    const val EYEBROW = "Models"
    const val TITLE = "Models & voices"
    const val LEDE =
        "Downloads are checked against a pinned size and SHA-256 before they can be used. " +
            "Installing never turns a stage on."
    const val IMPORT = "Import file…"
    const val ADD = "Add model"
    const val SEARCH = "Search models, families or languages"

    const val GROUP_SPEECH = "Speech recognition"
    const val GROUP_SPEECH_DETAIL = "Whisper · 99 languages, Arabic and English included"
    const val GROUP_TRANSLATION = "Translation"
    const val GROUP_TRANSLATION_DETAIL = "Local GGUF models · your text never leaves the Mac"
    const val GROUP_VOICE = "Voice"
    const val GROUP_VOICE_DETAIL = "Supertonic 3 · ten styles, plus voices already on this Mac"

    const val STORAGE = "Storage"
    const val STORAGE_DETAIL = "Stored in Application Support. Removing a model asks first and keeps your setup."
    const val OPEN_FOLDER = "Open folder"
    const val VERIFY_TITLE = "How verification works"
    const val VERIFY_DETAIL =
        "Each file carries an exact size and SHA-256. If either differs, Hearth refuses to load it and says why."
    const val VERIFY_BANNER = "Nothing downloads, installs or loads without passing that check."
    const val OWN_TITLE = "Bring your own"
    const val OWN_DETAIL = "Any Whisper GGML file or GGUF translation model works when you import it."
    const val OWN_ACTION = "Choose a file…"

    const val EMPTY = "Nothing matches this search."
    const val EMPTY_DETAIL = "Clear the filters to see the full library."
    const val REFRESH = "Re-check installed files"
    const val REFRESHING = "Checking installed files…"

    fun groupTitle(stage: PipelineStage): String = when (stage) {
        PipelineStage.STT -> GROUP_SPEECH
        PipelineStage.TRANSLATION -> GROUP_TRANSLATION
        PipelineStage.TTS -> GROUP_VOICE
    }

    fun groupDetail(stage: PipelineStage): String = when (stage) {
        PipelineStage.STT -> GROUP_SPEECH_DETAIL
        PipelineStage.TRANSLATION -> GROUP_TRANSLATION_DETAIL
        PipelineStage.TTS -> GROUP_VOICE_DETAIL
    }

    fun sectionFor(screen: DesktopScreen): List<Pair<DesktopScreen, String>> =
        DesktopScreen.modelsSection.map { it to it.subnavLabelOf() }

    private fun DesktopScreen.subnavLabelOf(): String = when (this) {
        DesktopScreen.MODELS -> "Library"
        DesktopScreen.PRESETS -> "Presets"
        DesktopScreen.DOWNLOADS -> "Downloads"
        else -> title
    }
}
