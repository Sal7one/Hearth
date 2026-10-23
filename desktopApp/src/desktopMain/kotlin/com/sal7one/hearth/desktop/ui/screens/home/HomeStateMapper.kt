package com.sal7one.hearth.desktop.ui.screens.home

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.selection
import java.util.Locale
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.language.pipelineLanguages
import com.sal7one.hearth.desktop.ui.language.stageSetupState

/**
 * Resolves the Home screen's state from the real configuration and engine
 * descriptors. Nothing here invents a model, a language or a count: a missing
 * descriptor becomes a null chip, not a placeholder name.
 */
internal fun homeState(
    config: PipelineConfig,
    capabilities: List<EngineDescriptor>,
    installedModels: Int,
    verifiedModels: Int,
    statusText: String,
    statusLive: Boolean,
    catalog: List<DesktopModelCard> = emptyList(),
    installations: Map<String, DesktopModelStatus> = emptyMap(),
    verifyingSelections: Map<String,String> = emptyMap(), verificationIssues: Map<String,String> = emptyMap(),
): HomeState {
    fun descriptor(stage: PipelineStage): EngineDescriptor? {
        val id = config.selection(stage)?.engineId ?: return null
        return capabilities.firstOrNull { it.id == id }
    }

    val speech = descriptor(PipelineStage.STT)
    val translation = descriptor(PipelineStage.TRANSLATION)
    val voice = descriptor(PipelineStage.TTS)
    val states=PipelineStage.entries.filter {it==PipelineStage.STT || config.selection(it)!=null}.associateWith {stage ->
        stageSetupState(config,stage,capabilities,installations,catalog,verifyingSelections,verificationIssues)
    }
    val recognitionReady=states[PipelineStage.STT]?.let {it.issue==null && !it.verifying}==true
    val setup=states.entries.firstOrNull {it.value.issue!=null}
    val pending=states.entries.firstOrNull {it.value.verifying}
    val verifying=setup==null && pending!=null
    val languages=pipelineLanguages(config,capabilities,catalog)

    return HomeState(
        sourceLanguage = config.sourceLanguage,
        targetLanguage = config.targetLanguage,
        sourceLabel = languageName(config.sourceLanguage),
        targetLabel = languageName(config.targetLanguage),
        speechEngine = speech?.shortName(),
        translationEngine = translation?.shortName(),
        voiceEngine = voice?.shortName(),
        installedModels = installedModels,
        verifiedModels = verifiedModels,
        recognitionReady = recognitionReady,
        statusText = when {
            statusLive || statusText in setOf("Closing","Needs attention") -> statusText
            verifying -> "Checking saved setup"
            (setup!=null || languages.issue!=null) && statusText=="Ready" -> "Setup needed"
            else -> statusText
        },
        statusLive = statusLive,
        languages = languages,
        setupIssue = setup?.value?.issue,
        setupStage = setup?.key ?: pending?.key,
        setupVerifying = verifying,
        verificationMessage = pending?.value?.message,
        onDevice = listOfNotNull(speech, translation, voice).none { it.kind == EngineKind.CLOUD },
    )
}

/** "English" for en, "Detect" for auto, the raw tag when the JVM has no name. */
internal fun languageName(code: String): String {
    if (code.isBlank() || code == "auto") return "Detect"
    val display = runCatching { Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH) }.getOrNull()
    return display?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase()
}

/** Chips are narrow: drop a trailing descriptor parenthetical rather than wrap. */
private fun EngineDescriptor.shortName(): String {
    val base = name.substringBefore(" (").trim()
    return if (base.length > 26) base.take(25) + "…" else base
}
