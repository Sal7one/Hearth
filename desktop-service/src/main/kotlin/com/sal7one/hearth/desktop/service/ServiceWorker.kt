package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.CredentialResolver
import com.sal7one.hearth.core.HearthPipeline
import com.sal7one.hearth.core.PipelineInput
import com.sal7one.hearth.core.PipelineRequest
import com.sal7one.hearth.core.PipelineSession
import com.sal7one.hearth.core.PipelineSnapshot
import com.sal7one.hearth.core.PipelineStatus
import com.sal7one.hearth.core.service.ServiceContract
import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceLimits
import com.sal7one.hearth.core.service.ServiceOperation
import com.sal7one.hearth.desktop.runtime.DesktopEngines
import com.sal7one.hearth.desktop.service.WorkerMessages.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * The engine worker process. It owns every native handle and loaded model, so ending this
 * process is what returns their memory to the computer. It runs one job at a time for the
 * host and stops when told to or when the host's end of the socket closes.
 */
object ServiceWorker {
    fun run(socket: Path, dataDirectory: Path) {
        // Outputs the engines create stay in a folder only this service uses and purges.
        val private = PrivateFiles.directory(ServiceDirectory(dataDirectory).root.resolve("engine"))
        SocketChannel.open(StandardProtocolFamily.UNIX).use { connection ->
            connection.connect(UnixDomainSocketAddress.of(socket))
            val channel = WorkerChannel(connection)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engines = DesktopEngines(CredentialResolver { null }, private, nativeRuntimeDirectory = dataDirectory)
            val pipeline = HearthPipeline(engines, scope)
            val active = AtomicReference<Pair<String, PipelineSession>?>()
            val cancelled = ConcurrentHashMap.newKeySet<String>()
            try {
                channel.send(WorkerMessages.simple("ready"))
                while (true) {
                    val message = channel.receive() ?: break
                    when (message.text("type")) {
                        "capabilities" -> channel.send(buildJsonObject { put("type", "capabilities"); put("engines", WorkerMessages.engines(engines.capabilities())) })
                        "run" -> {
                            val job = WorkerMessages.run(message)
                            scope.launch { channel.send(WorkerMessages.done(job.key, execute(job, pipeline, active, cancelled, scope, private))) }
                        }
                        "cancel" -> {
                            val key = message.text("key")
                            cancelled += key // A cancel can arrive before the session exists.
                            active.get()?.takeIf { it.first == key }?.second?.let { session -> scope.launch { session.cancel() } }
                        }
                        "exit" -> break
                    }
                }
            } finally {
                runBlocking {
                    withTimeoutOrNull(3_000) { active.get()?.second?.cancel() }
                    withTimeoutOrNull(2_000) { scope.coroutineContext[Job]?.cancelAndJoin() }
                }
                runCatching { engines.close() }
                purgeGeneratedAudio(private)
            }
        }
    }

    private suspend fun execute(job: WorkerJob, pipeline: HearthPipeline, active: AtomicReference<Pair<String, PipelineSession>?>, cancelled: Set<String>,
                                scope: CoroutineScope, private: Path): WorkerOutcome {
        val session = try {
            val input = job.audio?.let { PipelineInput.AudioFile(it) } ?: PipelineInput.Text(requireNotNull(job.text) { "A text job carries no text" })
            pipeline.start(PipelineRequest(input, job.config))
        } catch (failure: Exception) {
            return WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, failure.message ?: failure.toString())
        }
        val entry = job.key to session
        active.set(entry)
        // One job runs at a time and this folder is the worker's own, so its size is the speech generated so far.
        // A voice that would pass the output bound is stopped while it is writing, not after minutes of work.
        val tooLarge = AtomicBoolean()
        val watcher = if (job.config.voice == null) null else scope.launch {
            while (true) {
                delay(500)
                if (directoryBytes(private.resolve("generated-audio")) > ServiceLimits.MAX_OUTPUT_BYTES) { tooLarge.set(true); session.cancel(); break }
            }
        }
        try {
            if (job.key in cancelled) session.cancel()
            val snapshot = session.state.first { it.terminal }
            if (tooLarge.get()) return WorkerOutcome.Failed(ServiceErrorCode.OUTPUT_TOO_LARGE, ServiceOutputs.TOO_LARGE)
            return when (snapshot.status) {
                PipelineStatus.COMPLETED -> try { ServiceOutputs.write(job, snapshot) } catch (failure: Exception) {
                    WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, failure.message ?: failure.toString())
                }
                PipelineStatus.CANCELLED -> WorkerOutcome.Cancelled
                else -> WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, snapshot.error ?: "The engine failed without a message")
            }
        } finally {
            watcher?.cancel()
            active.compareAndSet(entry, null)
            // Generated audio lives on only as the job's own output file.
            session.state.value.audio.forEach { runCatching { Files.deleteIfExists(Path.of(it.path)) } }
        }
    }

    internal fun purgeGeneratedAudio(private: Path) { runCatching { deleteTree(private.resolve("generated-audio")) } }

    private fun directoryBytes(directory: Path): Long = runCatching {
        if (!Files.isDirectory(directory)) 0L else Files.list(directory).use { files -> files.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
    }.getOrDefault(0L)
}

/** Turns a completed pipeline snapshot into the output files and short summary the contract names. */
internal object ServiceOutputs {
    const val TOO_LARGE = "The generated speech would exceed the 64 MiB output bound; send less text"

    fun write(job: WorkerJob, snapshot: PipelineSnapshot): WorkerOutcome {
        val directory = PrivateFiles.directory(Path.of(job.outputDirectory))
        val finals = snapshot.segments.filter { it.final }
        val outputs = mutableListOf<JobOutput>()
        var spoken: Pair<Double, Int>? = null
        if (job.operation.takesAudio) {
            val transcript = buildJsonObject {
                putJsonArray("segments") {
                    for (segment in finals) add(buildJsonObject {
                        put("id", segment.id); put("startMs", segment.startMs); put("endMs", segment.endMs); put("text", segment.original)
                        segment.translation?.let { put("translation", it) }
                    })
                }
            }
            outputs += file(directory, "transcript.json", "application/json", transcript.toString().toByteArray(Charsets.UTF_8))
            outputs += file(directory, "transcript.vtt", "text/vtt", ServiceContract.webVtt(finals).toByteArray(Charsets.UTF_8))
        }
        if (job.operation == ServiceOperation.TRANSLATE) {
            val translated = snapshot.translatedText
            if (translated.isBlank()) return WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, "The translation engine returned no text")
            outputs += file(directory, "translation.txt", "text/plain",translated.toByteArray(Charsets.UTF_8))
        }
        if (job.config.voice != null) {
            if (snapshot.audio.isEmpty()) {
                if (job.operation == ServiceOperation.SPEAK) return WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, "The voice returned no audio")
            } else {
                val parts = snapshot.audio.map { Path.of(it.path) }
                if (44 + parts.sumOf { (Files.size(it) - 44).coerceAtLeast(0) } > ServiceLimits.MAX_OUTPUT_BYTES) {
                    return WorkerOutcome.Failed(ServiceErrorCode.OUTPUT_TOO_LARGE, TOO_LARGE)
                }
                val target = directory.resolve("speech.wav")
                spoken = joinWaves(parts, target)
                PrivateFiles.restrict(target, directory = false)
                outputs += JobOutput("speech.wav", "audio/wav", Files.size(target), sha256Hex(target))
            }
        }
        val summary = buildJsonObject {
            if (job.operation.takesAudio) put("segments", finals.size)
            if (job.config.sourceLanguage != "auto") put("language", job.config.sourceLanguage)
            if (job.config.translation != null) put("characters", snapshot.translatedText.length)
            spoken?.let { (seconds, rate) -> put("audioSeconds", seconds); put("sampleRate", rate) }
        }
        return WorkerOutcome.Completed(summary, outputs)
    }

    private fun file(directory: Path, name: String, mime: String, bytes: ByteArray): JobOutput {
        PrivateFiles.write(directory.resolve(name), bytes)
        return JobOutput(name, mime, bytes.size.toLong(), sha256Hex(bytes))
    }

    /** One PCM16 mono WAV from the voice's per-segment files; a mixed format is an engine fault, not something to hide. */
    private fun joinWaves(parts: List<Path>, target: Path): Pair<Double, Int> {
        var rate = 0
        var frames = 0L
        for (part in parts) AudioSystem.getAudioInputStream(part.toFile()).use { stream ->
            val format = stream.format
            check(format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == 16 && format.channels == 1 && !format.isBigEndian) { "The voice returned an unexpected audio format" }
            val current = format.sampleRate.toInt()
            check(rate == 0 || rate == current) { "The voice changed its sample rate within one job" }
            rate = current; frames += stream.frameLength
        }
        check(rate > 0 && frames in 1..rate.toLong() * 7_200) { "The voice returned no audio or more than two hours" }
        Files.newOutputStream(target).use { output ->
            val bytes = frames * 2
            output.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes.toInt()).array())
            for (part in parts) AudioSystem.getAudioInputStream(part.toFile()).use { it.transferTo(output) }
        }
        return frames.toDouble() / rate to rate
    }
}
