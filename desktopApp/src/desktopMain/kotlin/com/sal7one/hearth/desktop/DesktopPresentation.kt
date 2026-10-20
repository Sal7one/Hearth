package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CaptionSegment
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import java.nio.file.Path
import java.util.Locale

internal enum class WorkspaceInput(val title: String) { MICROPHONE("Microphone"), AUDIO_FILE("Audio file"), TEXT("Text") }
internal enum class DesktopPage(val title: String) { WORKSPACE("Workspace"), MODELS("Models & providers"), INTEGRATION("Integration") }
internal data class WorkspaceDraft(val text: String = "", val audioPath: String = "", val deviceId: String? = null)
internal data class ModelFilters(val query: String = "", val stage: PipelineStage? = null,
    val language: String? = null, val onlyInstalled: Boolean = false)

internal fun modelSupportsLanguage(languages: Set<String>, requested: String?): Boolean =
    requested == null || languages.isEmpty() || "multilingual" in languages || requested in languages

internal fun languageLabel(code: String): String = if (code == "multilingual") "Multiple languages"
    else "${Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).ifBlank { code }} ($code)"

internal fun isCurrentModelSelection(current: EngineSelection?, verified: EngineSelection): Boolean =
    current?.engineId == verified.engineId && current.modelId == verified.modelId && current.modelPath == verified.modelPath &&
        verified.options.all { (key, value) -> current.options[key] == value }

/** An explicit setup import reconnects verified installed IDs, never another engine or an unknown model. */
internal fun reconnectInstalledModels(config: PipelineConfig, installed: (String) -> EngineSelection?): PipelineConfig =
    PipelineStage.entries.fold(config) { current, stage ->
        val route = current.selection(stage)
        val verified = route?.modelId?.takeIf(String::isNotBlank)?.let(installed)
        if (route != null && verified != null && verified.engineId == route.engineId && verified.modelId == route.modelId)
            current.useVerifiedModel(stage, verified)
        else current
    }

/** Never export interim recognition or pass it off as a completed transcript. */
internal fun finalTranscript(snapshot: PipelineSnapshot?, translated: Boolean): String = snapshot?.segments
    ?.filter { it.final }
    ?.mapNotNull { if (translated) it.translation?.takeIf(String::isNotBlank) else it.original.takeIf(String::isNotBlank) }
    ?.joinToString("\n").orEmpty()

internal data class OverlayCaption(val text: String, val original: String?, val partial: Boolean)

internal fun overlayCaption(segments: List<CaptionSegment>, showOriginal: Boolean,
    translationEnabled: Boolean = false, showOriginalWhilePending: Boolean = true): OverlayCaption? {
    val latest = segments.lastOrNull { it.original.isNotBlank() || !it.translation.isNullOrBlank() } ?: return null
    val translated = latest.translation?.takeIf(String::isNotBlank)
    if (translationEnabled && translated == null && !showOriginalWhilePending) return null
    return OverlayCaption(translated ?: latest.original,
        latest.original.takeIf { showOriginal && translated != null && it != translated }, !latest.final)
}

/** An invalid advanced edit must not silently discard another engine option. */
internal fun parseEngineOptions(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for ((index, raw) in text.lineSequence().withIndex()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val separator = line.indexOf('=')
        require(separator > 0) { "Option line ${index + 1} must use name=value" }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) { "Invalid option name on line ${index + 1}" }
        require(name !in result) { "Duplicate option: $name" }
        require(value.length <= 4096) { "Option $name is too long" }
        result[name] = value
        require(result.size <= 32) { "At most 32 engine options are allowed" }
    }
    return result
}

internal fun desktopDataDirectory(
    osName: String = System.getProperty("os.name"),
    userHome: String = System.getProperty("user.home"),
    environment: Map<String, String> = System.getenv(),
): Path {
    environment["HEARTH_DATA_DIRECTORY"]?.takeIf(String::isNotBlank)?.let { return Path.of(it).toAbsolutePath().normalize() }
    return when {
        osName.startsWith("Windows", ignoreCase = true) -> Path.of(environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
            ?: Path.of(userHome, "AppData", "Local").toString(), "Hearth")
        osName.startsWith("Mac", ignoreCase = true) -> Path.of(userHome, "Library", "Application Support", "Hearth")
        else -> Path.of(environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)
            ?: Path.of(userHome, ".local", "share").toString(), "hearth")
    }
}
