package com.sal7one.transiber.settings

/** Browsing settings never selects an inference engine or starts a network request. */
internal enum class SettingsLocation(val label: String) { LOCAL("Local"), CLOUD("Cloud") }
internal enum class SettingsFeature(val label: String) {
    SPEECH("Speech recognition"), TRANSLATION("Translation"), VOICES("Voices & read aloud"), CAMERA("Camera & screen reading")
}

internal fun settingsLocations(cloudAvailable: Boolean): List<SettingsLocation> =
    if (cloudAvailable) SettingsLocation.entries else listOf(SettingsLocation.LOCAL)

internal fun settingsFeatures(location: SettingsLocation): List<SettingsFeature> =
    SettingsFeature.entries.filter { location == SettingsLocation.LOCAL || it != SettingsFeature.CAMERA }

/** A new directory entry overrides the tab; returning from model setup preserves it. */
internal data class SettingsTabState(val selected: SettingsLocation, val entry: Int) {
    fun enter(requested: SettingsLocation, revision: Int, cloudAvailable: Boolean): SettingsTabState {
        val next = if (revision == entry) selected else requested
        return SettingsTabState(next.takeIf { it in settingsLocations(cloudAvailable) } ?: SettingsLocation.LOCAL, revision)
    }
}
