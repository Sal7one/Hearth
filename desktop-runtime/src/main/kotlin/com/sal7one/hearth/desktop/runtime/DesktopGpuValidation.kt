package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopWhisperNative
import com.sal7one.common_jni.translation.LocalTranslationNative
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Explicit file-only hardware validation, also callable by the packaged launcher. */
object DesktopGpuValidation {
    fun run(backend: String, whisper: Path, euroLlm: Path, wav: Path, receipt: Path) {
        val report = linkedMapOf<String, JsonElement>()
        fun put(key: String, value: String) { report[key] = JsonPrimitive(value) }
        put("backend", backend); put("asOf", Instant.now().toString()); put("platform", DesktopNativeLibraries.platform)
        put("osVersion", System.getProperty("os.version"))
        report["audioRecorded"] = JsonPrimitive(false); report["audioPlayed"] = JsonPrimitive(false)
        report["timingsAreBenchmark"] = JsonPrimitive(false)
        val timings = linkedMapOf<String, Long>()
        fun <T> measured(key: String, action: () -> T): T {
            val start = System.nanoTime()
            return action().also { timings[key] = (System.nanoTime() - start) / 1_000_000 }
        }
        try {
            require(System.getProperty("hearth.native.dir") == null) { "GPU validation requires the packaged native resources, without an override" }
            val mode = when (backend) { "metal" -> 1; "cuda" -> 2; else -> error("Choose metal or cuda") }
            val speech = whisper.toRealPath(); val translation = euroLlm.toRealPath(); val audio = wav.toRealPath()
            val whisperHash = sha256(speech); val euroHash = sha256(translation)
            require(whisperHash in setOf("818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
                "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b")) { "Use the pinned Whisper tiny Q5_1 or tiny.en Q5_1 fixture" }
            require(euroHash == "f22ab4b628f6e3cd12ec2e5aa6d1e460be2fa83f25cb6a8f5dd6d4d77b285b66") { "Use the pinned EuroLLM 1.7B Q4_K_M fixture" }
            put("whisperSha256", whisperHash); put("translationSha256", euroHash); put("wavSha256", sha256(audio))
            val nativeManifest = javaClass.getResourceAsStream("/native/${DesktopNativeLibraries.platform}/manifest.json")
                ?.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
                ?: error("Packaged native provenance is missing")
            report["nativeLibraries"] = JsonObject(nativeManifest.getValue("libraries").jsonObject.mapValues { (_, value) ->
                JsonObject(value.jsonObject.filterKeys { it != "sources" })
            })
            put("nativeSourceSetSha256", nativeManifest.getValue("sourceSetSha256").jsonPrimitive.content)
            check(DesktopWhisperNative.protocolVersion() == DesktopWhisperNative.PROTOCOL_VERSION && LocalTranslationNative.promptProtocolVersion() == 5) { "GPU native protocol mismatch" }
            check(DesktopWhisperNative.gpuAvailable(mode, 0)) { "Whisper cannot initialize $backend device 0" }
            check(LocalTranslationNative.gpuAvailable(mode, 0)) { "Translation cannot initialize $backend device 0" }
            report["speechDevices"] = Json.parseToJsonElement(DesktopWhisperNative.gpuDevices(mode).toString(Charsets.UTF_8))
            report["translationDevices"] = Json.parseToJsonElement(LocalTranslationNative.gpuDevices(mode).toString(Charsets.UTF_8))
            val samples = readAudio(audio)
            for ((key, requested) in listOf("cpu" to 0, backend to mode)) {
                val stt = measured("${key}SpeechLoadMs") { DesktopWhisperNative.createWithBackend(speech.toString().toByteArray(), "en".toByteArray(), 2, requested, 0) }
                try {
                    val transcript = measured("${key}SpeechInferenceMs") { DesktopWhisperNative.transcribe(stt, samples).toString(Charsets.UTF_8).trim() }
                    check(transcript.isNotBlank()) { "$key speech returned empty text" }
                    val placement = DesktopWhisperNative.executionInfo(stt).toString(Charsets.UTF_8)
                    check("completedInference=1" in placement) { "Speech did not complete inference: $placement" }
                    if (requested != 0) check("GPU" in placement && "total size" in placement) { "Speech GPU allocation was not proven: $placement" }
                    put("${key}Transcript", transcript); put("${key}SpeechExecution", placement)
                } finally { DesktopWhisperNative.destroy(stt) }
                val mt = measured("${key}TranslationLoadMs") { LocalTranslationNative.createWithBackend(translation.toString().toByteArray(), 2, 2, requested, 0) }
                try {
                    val prompt = DesktopTranslationPrompts.prompt("Do not send the payment today. The total is 42 dollars. Keep the door closed.", "en", "ar", "eurollm")
                    val output = measured("${key}TranslationInferenceMs") { LocalTranslationNative.translate(mt, prompt.toByteArray()).toString(Charsets.UTF_8).trim() }
                    check(output.any { it in '\u0600'..'\u06ff' }) { "$key translation did not produce Arabic: $output" }
                    val placement = LocalTranslationNative.executionInfo(mt).toString(Charsets.UTF_8)
                    check("completedInference=1" in placement) { "Translation did not complete inference: $placement" }
                    if (requested != 0) check("offloaded " in placement && "model buffer size" in placement) { "Translation GPU allocation was not proven: $placement" }
                    put("${key}ArabicTranslation", output); put("${key}TranslationExecution", placement)
                } finally { LocalTranslationNative.destroy(mt) }
            }
            put("status", "PASS")
        } catch (error: Throwable) {
            put("status", "FAIL"); put("error", error.message ?: error.javaClass.name)
            throw error
        } finally {
            report["timingsMs"] = buildJsonObject { timings.forEach { (name, value) -> put(name, value) } }
            Files.createDirectories(receipt.toAbsolutePath().parent)
            Files.writeString(receipt, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(report)) + "\n")
        }
    }

    private fun readAudio(path: Path): FloatArray = AudioSystem.getAudioInputStream(path.toFile()).use { raw ->
        val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false)
        AudioSystem.getAudioInputStream(format, raw).use { input ->
            val bytes = input.readNBytes(30 * 16_000 * 2 + 1)
            require(bytes.size in 16_000..(30 * 16_000 * 2) && bytes.size % 2 == 0) { "Provide 0.5–30 seconds of readable audio" }
            FloatArray(bytes.size / 2) { i -> (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 255)) / 32768f) }
        }
    }
}
