package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.GeneratedAudio
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DesktopSystemVoice(val id: String, val name: String, val language: String, val gender: String = "unknown")

object DesktopSystemSpeech {
    private val windows = System.getProperty("os.name").startsWith("Windows", true)
    private val mac = System.getProperty("os.name").contains("Mac", true)
    val available: Boolean get() = windows || mac && Files.isExecutable(Path.of("/usr/bin/say"))

    suspend fun voices(): List<DesktopSystemVoice> {
        check(available) { "System speech is unavailable on this platform" }
        if (mac) {
            val output = process(listOf("/usr/bin/say", "-v", "?"))
            return output.lineSequence().mapNotNull { line ->
                val match = Regex("^(.+?)\\s+([a-z]{2,3}_[A-Z]{2})\\s+#.*$").matchEntire(line) ?: return@mapNotNull null
                val name = match.groupValues[1].trim()
                DesktopSystemVoice(name, name, match.groupValues[2].replace('_', '-'))
            }.toList()
        }
        val script = """
            Add-Type -AssemblyName System.Speech
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            ${'$'}s = [System.Speech.Synthesis.SpeechSynthesizer]::new()
            try { @(${'$'}s.GetInstalledVoices() | Where-Object Enabled | ForEach-Object {
              @{ id = ${'$'}_.VoiceInfo.Name; language = ${'$'}_.VoiceInfo.Culture.Name; gender = ${'$'}_.VoiceInfo.Gender.ToString().ToLowerInvariant() }
            }) | ConvertTo-Json -Compress } finally { ${'$'}s.Dispose() }
        """.trimIndent()
        val json = Json.parseToJsonElement(process(powershell(script)))
        val voices = if (json is JsonArray) json else JsonArray(listOf(json))
        return voices.map { value -> value.jsonObject.let { DesktopSystemVoice(it.getValue("id").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content, it.getValue("language").jsonPrimitive.content, it.getValue("gender").jsonPrimitive.content) } }
    }

    internal suspend fun synthesize(text: String, language: String, voiceId: String, directory: Path): GeneratedAudio {
        require(text.isNotBlank() && text.length <= 32000) { "System read aloud accepts 1–32000 characters" }
        val requested = Locale.forLanguageTag(language).language
        require(requested.isNotBlank() && language != "auto") { "Choose an explicit language for system speech" }
        val installed = voices()
        val voice = if (voiceId.isNotBlank()) installed.firstOrNull { it.id == voiceId } else installed.firstOrNull { Locale.forLanguageTag(it.language).language == requested }
        require(voice != null && Locale.forLanguageTag(voice.language).language == requested) { "Install/select a system voice for $language" }
        val output = outputFile(directory)
        val input = Files.createTempFile(output.parent, "speech-", ".txt")
        try {
            Files.writeString(input, text)
            if (mac) process(listOf("/usr/bin/say", "-v", voice.id, "-o", output.toString(), "--data-format=LEI16@22050", "-f", input.toString()))
            else {
                val script = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = [System.Speech.Synthesis.SpeechSynthesizer]::new()
                    try {
                      ${'$'}s.SelectVoice(${'$'}env:HEARTH_VOICE)
                      ${'$'}s.SetOutputToWaveFile(${'$'}env:HEARTH_OUTPUT)
                      ${'$'}s.Speak([System.IO.File]::ReadAllText(${'$'}env:HEARTH_INPUT, [System.Text.Encoding]::UTF8))
                    } finally { ${'$'}s.Dispose() }
                """.trimIndent()
                process(powershell(script), mapOf("HEARTH_VOICE" to voice.id, "HEARTH_OUTPUT" to output.toString(), "HEARTH_INPUT" to input.toString()))
            }
            check(Files.isRegularFile(output) && Files.size(output) > 44) { "System voice returned no audio" }
            return GeneratedAudio(output.toString(), text = text)
        } catch (error: Throwable) { Files.deleteIfExists(output); throw error }
        finally { Files.deleteIfExists(input) }
    }
    private fun powershell(script: String) = listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))

    private suspend fun process(command: List<String>, environment: Map<String, String> = emptyMap()): String {
        val active = AtomicReference<Process?>()
        val cancelled = AtomicBoolean()
        val owner = currentCoroutineContext()
        return joinedBlocking({ cancelled.set(true); active.get()?.destroyForcibly() }) {
            owner.ensureActive()
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment().putAll(environment)
            val child = builder.start(); active.set(child)
            try {
                if (cancelled.get()) { child.destroyForcibly(); owner.ensureActive() }
                val output = StringBuilder()
                child.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        check(output.length + count <= 65536) { "System speech diagnostics exceeded the size bound" }
                        output.append(buffer, 0, count); owner.ensureActive()
                    }
                }
                val status = child.waitFor()
                check(status == 0) { "System speech failed ($status): ${output.toString().take(2048)}" }
                output.toString()
            } finally {
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(); child.inputStream.close()
            }
        }
    }
}
