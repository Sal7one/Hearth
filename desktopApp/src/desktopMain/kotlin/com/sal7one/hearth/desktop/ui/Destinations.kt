package com.sal7one.hearth.desktop.ui

/**
 * Every screen the desktop app can show. `navLabel` marks the six top-bar entries;
 * the rest are reached through the Models and Settings section sub-navigation.
 */
internal enum class DesktopSection { MODELS, SETTINGS }

internal enum class DesktopScreen(
    val id: String,
    val title: String,
    val navLabel: String? = null,
    val section: DesktopSection? = null,
) {
    HOME("home", "Home", navLabel = "Home"),
    CAPTIONS("captions", "Live captions", navLabel = "Captions"),
    TRANSLATE("translate", "Type to translate", navLabel = "Translate"),
    OVERLAY("overlay", "Overlay", navLabel = "Overlay"),
    MODELS("models", "Models & voices", navLabel = "Models", section = DesktopSection.MODELS),
    PRESETS("presets", "Presets", section = DesktopSection.MODELS),
    DOWNLOADS("downloads", "Downloads", section = DesktopSection.MODELS),
    INTEGRATIONS("integrations", "Integrations", navLabel = "Integrations"),
    SPEECH("speech", "Speech recognition", section = DesktopSection.SETTINGS),
    TRANSLATION_SETTINGS("translation", "Translation", section = DesktopSection.SETTINGS),
    VOICES("voices", "Voices", section = DesktopSection.SETTINGS),
    CONNECTIONS("connections", "Connections", section = DesktopSection.SETTINGS),
    FILES("files", "Files & storage", section = DesktopSection.SETTINGS),
    APPEARANCE("appearance", "Appearance", section = DesktopSection.SETTINGS),
    CONVERSATION("conversation", "Conversation"),
    CAMERA("camera", "Camera & photos"),
    READING("reading", "Screen & manga");

    companion object {
        val topLevel: List<DesktopScreen> = entries.filter { it.navLabel != null }
        val modelsSection: List<DesktopScreen> = entries.filter { it.section == DesktopSection.MODELS }
        val settingsSection: List<DesktopScreen> = entries.filter { it.section == DesktopSection.SETTINGS }

        fun fromId(id: String): DesktopScreen = entries.firstOrNull { it.id == id } ?: HOME
    }
}

/** Sub-nav labels differ from page titles on purpose (short in the rail). */
internal val DesktopScreen.subnavLabel: String
    get() = when (this) {
        DesktopScreen.MODELS -> "Library"
        DesktopScreen.SPEECH -> "Speech"
        DesktopScreen.TRANSLATION_SETTINGS -> "Translation"
        DesktopScreen.VOICES -> "Voices"
        DesktopScreen.CONNECTIONS -> "Connections"
        DesktopScreen.FILES -> "Files"
        DesktopScreen.APPEARANCE -> "Appearance"
        DesktopScreen.PRESETS -> "Presets"
        DesktopScreen.DOWNLOADS -> "Downloads"
        else -> title
    }
