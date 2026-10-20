package com.sal7one.transiber.settings

/** Device voices are built in; downloaded speech/translation/OCR models are not allowed on TV. */
internal fun settingsStageLocations(feature: SettingsFeature, cloudAvailable: Boolean,
    localModelsAllowed: Boolean): List<SettingsLocation> = buildList {
    if ((localModelsAllowed || feature == SettingsFeature.VOICES) && feature in settingsFeatures(SettingsLocation.LOCAL)) add(SettingsLocation.LOCAL)
    if (cloudAvailable && feature in settingsFeatures(SettingsLocation.CLOUD)) add(SettingsLocation.CLOUD)
}

internal fun settingsStageDestination(preferred: SettingsLocation, feature: SettingsFeature,
    cloudAvailable: Boolean, localModelsAllowed: Boolean): SettingsLocation? =
    settingsStageLocations(feature, cloudAvailable, localModelsAllowed).let { allowed ->
        preferred.takeIf { it in allowed } ?: allowed.firstOrNull()
    }
