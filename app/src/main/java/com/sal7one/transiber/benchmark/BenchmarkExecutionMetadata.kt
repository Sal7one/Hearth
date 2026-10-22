package com.sal7one.transiber.benchmark

/** Recognize resolved session evidence, never requested settings, build flags or device probes. */
internal object BenchmarkExecutionMetadata {
    fun actualAcceleration(executionInfo: String?): String? {
        val info = executionInfo?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        if (info == "cpu" || info.startsWith("cpu · ")) return "cpu"
        // Native GGUF adapters reject zero layers, invalid assignments and absent GPU model buffers.
        val placement = Regex("^(vulkan|opencl) · (.+) · loader assigned ([0-9]+)/([0-9]+) layers; gpu model buffers ([0-9]+(?:\\.[0-9]+)?) mib(?:[. ].*)?$").matchEntire(info)
        if (placement != null) {
            val (backend, device, placedText, totalText, bufferText) = placement.destructured
            val placed = placedText.toLongOrNull() ?: return null
            val total = totalText.toLongOrNull() ?: return null
            val buffers = bufferText.toDoubleOrNull() ?: return null
            return backend.takeIf { placed > 0 && placed <= total && buffers.isFinite() && buffers > 0 &&
                (backend != "opencl" || "adreno" in device) }
        }
        // TranscribePsy reports mel CPU separately; both loaded neural stages must agree.
        val stages = Regex("^transcribepsy · mel: cpu · encoder: ([^·]+) · decoder: ([^·]+)(?: ·.*)?$").matchEntire(info)
        if (stages != null) {
            val encoder = stages.groupValues[1].trim()
            val decoder = stages.groupValues[2].trim()
            if (encoder != decoder) return null
            return when {
                encoder.startsWith("opencl") -> "opencl"
                encoder.startsWith("vulkan") -> "vulkan"
                encoder.startsWith("cpu") -> "cpu"
                else -> null
            }
        }
        // transcribe.cpp asks the loaded model for its resolved primary backend.
        Regex("^.+ · frontend: cpu · primary compute: (vulkan|cpu)(?: ·.*)?$").matchEntire(info)
            ?.let { return it.groupValues[1] }
        if (info.startsWith("audar flash · bf16 encoder: cpu · qwen3-asr decoder: cpu ·") ||
            info.startsWith("owsm v4 base · frontend: cpu · encoder: cpu · decoder: cpu ·")) return "cpu"
        // Whisper's current `Vulkan GPU: … + CPU` describes a probe before model load.
        // Keep its actual placement unknown until native model/compute evidence is exposed.
        return null
    }

    fun configurationHash(candidate: BenchmarkCandidate, passes: Int, settings: String = ""): String? = candidate.artifactFingerprint?.let { pin ->
        benchmarkHash(listOf("hearth-benchmark-v3", candidate.task.name, pin, candidate.acceleration.orEmpty(),
            candidate.voiceFamily.orEmpty(), candidate.voiceId.orEmpty(), "passes=$passes", settings, "voice-rate=1;voice-steps=5",
            "speech-threads=4;whisper-threads=8;vosk-threads=2;context=3;utterance-ms=4000",
            BenchmarkAudio.NORMALIZATION).joinToString("\u0000").toByteArray())
    }

    fun canResume(result: BenchmarkResult, candidate: BenchmarkCandidate, hash: String, configurationHash: String?,
        source: String, target: String, device: String, passes: Int): Boolean = configurationHash != null &&
        result.error == null && result.texts.lastOrNull()?.isNotBlank() == true &&
        result.protocolVersion == 2 && result.task == candidate.task.name.lowercase(java.util.Locale.ROOT) &&
        result.identity == BenchmarkAccelerationPlan.identity(candidate) && result.inputHash == hash &&
        result.actualAcceleration == candidate.acceleration &&
        result.configurationHash == configurationHash && result.source == source && result.target == target &&
        result.device == device && result.computeMs.size == passes && result.computeMs.all { it.isFinite() && it >= 0 }
}
