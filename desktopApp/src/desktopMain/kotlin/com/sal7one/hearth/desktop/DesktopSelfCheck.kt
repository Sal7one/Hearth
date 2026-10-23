package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.HearthPipeline
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.desktop.media.DesktopMediaDecoder
import com.sal7one.hearth.desktop.runtime.DesktopEngines
import com.sal7one.hearth.desktop.runtime.DesktopSystemAudioCapture
import com.sal7one.hearth.integration.DesktopIntegrationServer
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Properties
import javax.sound.sampled.AudioSystem

/** Installer validation entry point. It opens no UI, microphone, model or remote connection. */
internal object DesktopSelfCheck {
    fun run(reportPath: Path) = runBlocking(Dispatchers.IO) {
        val report = Properties()
        val profile = reportPath.toAbsolutePath().parent.resolve("packaged-runtime-check")
        Files.createDirectories(profile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var engines: DesktopEngines? = null
        try {
            report.setProperty("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
            check(KeyPairGenerator.getInstance("EC").provider != null) { "Packaged runtime lacks EC cryptography for HTTPS" }
            check(AudioSystem.getAudioFileTypes().isNotEmpty()) { "Packaged runtime lacks sampled audio services" }
            check(DesktopMediaDecoder.available) { "Native media adapter is missing from the packaged runtime" }
            if (System.getProperty("os.name").startsWith("Mac", true)) {
                check(DesktopSystemAudioCapture.available) { DesktopSystemAudioCapture.availabilityReason ?: "Native Mac system audio helper is missing" }
                DesktopSystemAudioCapture.probe(profile)
                report.setProperty("system-audio", "helper protocol1 passed; permission not requested; live capture not tested")
            }
            val mediaInput = Files.createTempFile(profile, "media-check-", ".wav")
            var mediaOutput: Path? = null
            try {
                val samples = ByteArray(3_200)
                javax.sound.sampled.AudioInputStream(
                    samples.inputStream(), javax.sound.sampled.AudioFormat(16_000f, 16, 1, true, false), 1_600,
                ).use { AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, mediaInput.toFile()) }
                mediaOutput = DesktopMediaDecoder.decode(mediaInput, profile)
                check(Files.size(mediaOutput) > 44) { "Packaged media adapter returned no audio" }
                report.setProperty("media", "native decode passed")
            } finally {
                mediaOutput?.let(Files::deleteIfExists)
                Files.deleteIfExists(mediaInput)
            }
            val owned = DesktopEngines(CredentialResolver { null }, profile)
            engines = owned
            val descriptors = owned.capabilities()
            val platform = (if (System.getProperty("os.name").contains("Mac", true)) "macos" else "windows") +
                "-" + (if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64")
            // Optional ports differ by host. A bundled port must load successfully; absence
            // on an unsupported host is distinct from a broken library shipped in its JAR.
            val optionalPorts = mapOf(
                "qwen3-asr-local" to listOf("hearth_desktop_qwen3_asr"),
                "transcribepsy-local" to listOf("hearth_desktop_backend_speech", "hearth_transcribepsy"),
                "nemotron-asr-local" to listOf("hearth_desktop_backend_speech", "hearth_nemotron"),
                "moonshine-local" to listOf("hearth_desktop_backend_speech", "hearth_desktop_qwen3_asr"),
                "vosk-local" to listOf("hearth_desktop_backend_speech", "hearth_vosk"),
                "omnilingual-local" to listOf("hearth_desktop_backend_speech", "hearth_desktop_qwen3_asr"),
                "phonon-local" to listOf("hearth_desktop_backend_speech", "hearth_desktop_qwen3_asr"),
                "marian-local" to listOf("hearth_desktop_ct2"),
                "moss-tts-local" to listOf("hearth_desktop_moss"),
                "omnivoice-tts-local" to listOf("hearth_desktop_omnivoice"),
                "magpie-tts-local" to listOf("hearth_desktop_magpie"),
                "chatterbox-tts-local" to listOf("hearth_desktop_chatterbox"),
                "ming-local" to listOf("ming_jni"),
            )
            val bundledPorts = optionalPorts.filterValues { libraries -> libraries.all { library ->
                javaClass.getResource("/native/$platform/${System.mapLibraryName(library)}.payload") != null
            } }.keys
            for (id in listOf("whisper-local", "llama-translation-local", "supertonic-local") + bundledPorts) {
                val descriptor = descriptors.single { it.id == id }
                check(descriptor.available) { "$id: ${descriptor.unavailableReason}" }
                report.setProperty(id, "available; acceleration=" + descriptor.acceleration.sorted().joinToString(","))
            }
            val pipeline = HearthPipeline(owned, scope)
            if (javaClass.getResource("/native/$platform/${System.mapLibraryName("hearth_desktop_ocr")}.payload") != null) {
                com.sal7one.hearth.desktop.runtime.DesktopMangaOcr.verifyRuntimeContract()
                report.setProperty("manga-ocr", "CPU runtime and both JNI adapters loaded; retired handles rejected; no model inference in self-check")
            }
            DesktopIntegrationServer(pipeline, { PipelineConfig() }, profile).use { server ->
                server.start()
                val response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI(server.baseUrl + "/v1/capabilities"))
                        .header("Authorization", "Bearer " + server.apiToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                check(response.statusCode() == 200 && response.body().contains("protocolVersion")) { "Packaged localhost integration failed" }
            }
            report.setProperty("result", "PASS")
        } catch (failure: Throwable) {
            report.setProperty("result", "FAIL")
            report.setProperty("error", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            scope.cancel(); scope.coroutineContext[Job]?.join()
            engines?.close()
            Files.createDirectories(reportPath.toAbsolutePath().parent)
            Files.newOutputStream(reportPath).use { report.store(it, "Hearth packaged runtime check; no models or provider credentials") }
        }
    }
}
