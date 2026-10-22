package com.sal7one.hearth.desktop.ui.language

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.selection
import java.util.Locale
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import com.sal7one.hearth.desktop.ui.screens.settings.computeSettings

internal data class LanguageOption(val code: String, val label: String)
internal data class PipelineLanguageState(
    val source: String,
    val target: String,
    val sources: List<LanguageOption>,
    val targets: List<LanguageOption>,
    val canSwap: Boolean,
    val issue: String? = null,
)

private val broadLanguages = (Locale.getISOLanguages().toSet() + setOf("yue", "fil", "nb", "ckb"))
    .filter { it != "auto" && it.isNotBlank() }.toSet()
private val detectingSpeechEngines = setOf("whisper-local", "qwen3-asr-local", "nemotron-asr-local", "transcribepsy-local", "openai-stt")
// Exact code inventory in the pinned common-jni/.../whisper/upstream/src/whisper.cpp g_lang table.
internal val whisperLanguageCodes = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su yue").split(' ').toSet()

private val languageNames = ConcurrentHashMap<String, String>()
internal fun languageLabel(code: String): String = languageNames.computeIfAbsent(code) {
    if (it == "auto") "Detect language" else Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH).takeIf(String::isNotBlank) ?: it
}

private fun Set<String>.concrete(): Set<String> = filter { it != "auto" && it != "multilingual" && it.isNotBlank() }.toSet()
private fun EngineSelection?.codes(key: String, card: DesktopModelCard?): Set<String> =
    (card?.options?.get(key) ?: this?.options?.get(key)).orEmpty().split(',').map(String::trim).toSet().concrete()
private fun selectedCard(route: EngineSelection?, catalog: List<DesktopModelCard>): DesktopModelCard? {
    if (route == null) return null
    return catalog.firstOrNull { it.id == route.modelId && it.engineId == route.engineId }
}

/** Constraints come from the selected checkpoint; a directional model is never treated as multilingual. */
internal fun pipelineLanguages(
    config: PipelineConfig,
    capabilities: List<EngineDescriptor>,
    catalog: List<DesktopModelCard>,
    typedText: Boolean = false,
): PipelineLanguageState {
    val translation = config.translation
    val translationCard = selectedCard(translation, catalog)
    val speechCard = selectedCard(config.speech, catalog)
    val speech = capabilities.firstOrNull { it.id == config.speech?.engineId && it.stage == PipelineStage.STT }
    val translator = capabilities.firstOrNull { it.id == translation?.engineId && it.stage == PipelineStage.TRANSLATION }
    val explicitSources = translation.codes("sources", translationCard)
    val explicitTargets = translation.codes("targets", translationCard)
    val translationLanguages = translationCard?.languages?.concrete()?.takeIf { it.isNotEmpty() }
        ?: translator?.languages?.concrete()?.takeIf { it.isNotEmpty() }
    val speechLanguages = if (config.speech?.engineId == "whisper-local") {
        if (speechCard?.languages == setOf("en")) setOf("en") else whisperLanguageCodes
    } else speechCard?.languages?.takeUnless { "multilingual" in it }?.concrete()?.takeIf { it.isNotEmpty() }
        ?: speech?.languages?.concrete()?.takeIf { it.isNotEmpty() }
    var sourceCodes = explicitSources.ifEmpty { translationLanguages ?: if (!typedText) speechLanguages ?: broadLanguages else broadLanguages }
    if (!typedText && speechLanguages != null) sourceCodes = sourceCodes intersect speechLanguages
    var targetCodes = explicitTargets.ifEmpty { translationLanguages ?: broadLanguages }
    val reverseSources = sourceCodes
    val reverseTargets = targetCodes
    val family = translationCard?.options?.get("family") ?: translation?.options?.get("family")
    if (family == "riva-translate" && config.sourceLanguage != "en") targetCodes = targetCodes intersect setOf("en")
    if (family == "riva-translate" && config.targetLanguage != "en") sourceCodes = sourceCodes intersect setOf("en")
    val allowsAuto = !typedText && config.speech?.engineId in detectingSpeechEngines &&
        explicitSources.isEmpty() && (family != "riva-translate" || config.targetLanguage == "en") &&
        (translation == null || translator?.kind == EngineKind.CLOUD || config.speech?.engineId == "whisper-local") &&
        !(config.speech?.engineId == "whisper-local" && speechLanguages?.size == 1)
    if (allowsAuto) sourceCodes = sourceCodes + "auto"
    val issue = when {
        config.targetLanguage == "auto" -> "Choose an output language; detection is only available for speech input."
        config.sourceLanguage !in sourceCodes -> when {
            config.sourceLanguage == "auto" && typedText -> "Choose the language of your text before translating."
            explicitSources.size == 1 -> "This translation model needs ${languageLabel(explicitSources.single())} as its source language."
            else -> "Choose a source language supported by the selected speech and translation models."
        }
        config.targetLanguage !in targetCodes -> "Choose an output language supported by the selected translation model."
        else -> null
    }
    fun options(codes: Set<String>) = codes.map { LanguageOption(it, languageLabel(it)) }
        .sortedWith(compareBy<LanguageOption> { it.code != "auto" }.thenBy { it.label })
    val reverseRiva = family != "riva-translate" || config.sourceLanguage == "en" || config.targetLanguage == "en"
    return PipelineLanguageState(config.sourceLanguage, config.targetLanguage, options(sourceCodes), options(targetCodes),
        config.targetLanguage in reverseSources && config.sourceLanguage in reverseTargets && config.sourceLanguage != "auto" && reverseRiva, issue)
}

/** Only explicit model/engine Use may repair a forced direction; supported choices and all stages stay intact. */
internal fun normalizePipelineLanguages(config: PipelineConfig, capabilities: List<EngineDescriptor>, catalog: List<DesktopModelCard>): PipelineConfig {
    val route = config.translation ?: return config
    if (capabilities.none { it.id == route.engineId && it.stage == PipelineStage.TRANSLATION }) return config
    val card = selectedCard(route, catalog)
    val sources = route.codes("sources", card)
    val targets = route.codes("targets", card)
    return config.copy(
        sourceLanguage = if (sources.size == 1 && config.sourceLanguage !in sources) sources.single() else config.sourceLanguage,
        targetLanguage = if (targets.size == 1 && config.targetLanguage !in targets) targets.single() else config.targetLanguage,
    )
}

/** No disk IO or inferred credentials: readiness is based on actual verified model state and explicit configuration. */
internal fun stageSetupIssue(config: PipelineConfig, stage: PipelineStage, capabilities: List<EngineDescriptor>, installations: Map<String, DesktopModelStatus>, catalog: List<DesktopModelCard> = emptyList()): String? {
    val route = config.selection(stage) ?: return "Choose a ${when(stage) { PipelineStage.STT -> "speech"; PipelineStage.TRANSLATION -> "translation"; PipelineStage.TTS -> "voice" }} engine."
    val engine = capabilities.firstOrNull { it.id == route.engineId && it.stage == stage } ?: return "The selected engine is unavailable in this app."
    if (!engine.available) return engine.unavailableReason ?: "${engine.name} is unavailable on this computer."
    computeSettings(engine, route).issue?.let { return it }
    if (engine.requiresModel) {
        val card = selectedCard(route, catalog)?.takeIf { it.stage == stage }
        val status = installations[route.modelId]
        val installedPath = status?.path
        val matchesPath = route.modelPath.isNotBlank() && installedPath != null && runCatching {
            Path.of(route.modelPath).toAbsolutePath().normalize() == installedPath.toAbsolutePath().normalize()
        }.getOrDefault(false)
        if (card == null || status?.ready != true || !matchesPath) return "Choose a verified installed model for ${engine.name}."
    }
    if (engine.requiresCredential) {
        val host = runCatching { URI(route.endpoint).host }.getOrNull()
        val local = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        if (route.endpoint.isBlank() || route.credentialId.isBlank() && !local) return "Configure the endpoint and credential for ${engine.name}."
    }
    return null
}
