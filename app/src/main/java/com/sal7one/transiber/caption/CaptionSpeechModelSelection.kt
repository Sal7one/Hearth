package com.sal7one.transiber.caption

import com.sal7one.transiber.models.ModelRegistry

/** An explicit speech model never falls back to another model or its language metadata. */
internal fun selectCaptionSpeechModel(
    candidates: List<ModelRegistry.RegisteredModel>,
    modelId: String,
): ModelRegistry.RegisteredModel? =
    if (modelId.isBlank()) candidates.firstOrNull() else candidates.firstOrNull { it.id == modelId }
