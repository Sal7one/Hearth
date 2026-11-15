package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceOperation
import com.sal7one.hearth.desktop.service.WorkerMessages.text
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * A real process speaking the worker protocol with scripted engines, so the tests exercise
 * the actual socket, process start, cancellation grace and kill paths without model weights.
 * The job text chooses the behaviour: "slow" waits for a cancel, "hang" ignores it, "fail" fails.
 */
internal val fakeEngines = listOf(
    EngineDescriptor("fake-stt", "Fake speech", PipelineStage.STT, EngineKind.LOCAL),
    EngineDescriptor("fake-mt", "Fake translation", PipelineStage.TRANSLATION, EngineKind.LOCAL),
    EngineDescriptor("fake-tts", "Fake voice", PipelineStage.TTS, EngineKind.LOCAL),
    EngineDescriptor("cloud-mt", "Cloud translation", PipelineStage.TRANSLATION, EngineKind.CLOUD),
    EngineDescriptor("broken-stt", "Broken speech", PipelineStage.STT, EngineKind.LOCAL, available = false, unavailableReason = "Broken speech library failed to load"),
)

fun main(arguments: Array<String>) {
    SocketChannel.open(StandardProtocolFamily.UNIX).use { connection ->
        connection.connect(UnixDomainSocketAddress.of(Path.of(arguments[0])))
        val channel = WorkerChannel(connection)
        channel.send(WorkerMessages.simple("ready"))
        var slow: String? = null
        // Something real to measure: the worker holds this for its lifetime.
        val held = ByteArray(8 * 1024 * 1024) { 1 }
        while (true) {
            val message = channel.receive() ?: break
            when (message.text("type")) {
                "capabilities" -> channel.send(buildJsonObject { put("type", "capabilities"); put("engines", WorkerMessages.engines(fakeEngines)) })
                "exit" -> exitProcess(0)
                "cancel" -> if (slow == message.text("key")) { channel.send(WorkerMessages.done(slow!!, WorkerOutcome.Cancelled)); slow = null }
                "run" -> {
                    val job = WorkerMessages.run(message)
                    val directory = PrivateFiles.directory(Path.of(job.outputDirectory))
                    when (job.text) {
                        "slow" -> slow = job.key
                        "hang" -> Unit
                        "big" -> channel.send(WorkerMessages.done(job.key, WorkerOutcome.Failed(ServiceErrorCode.OUTPUT_TOO_LARGE, ServiceOutputs.TOO_LARGE)))
                        "fail" -> channel.send(WorkerMessages.done(job.key, WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, "The fake engine was asked to fail")))
                        else -> {
                            val outputs = mutableListOf<JobOutput>()
                            fun write(name: String, mime: String, bytes: ByteArray) {
                                PrivateFiles.write(directory.resolve(name), bytes)
                                outputs += JobOutput(name, mime, bytes.size.toLong(), sha256Hex(bytes))
                            }
                            val summary = buildJsonObject {
                                when (job.operation) {
                                    ServiceOperation.TRANSLATE -> { write("translation.txt", "text/plain; charset=utf-8", job.text!!.reversed().toByteArray()); put("characters", job.text!!.length) }
                                    ServiceOperation.SPEAK -> { write("speech.wav", "audio/wav", ByteArray(44 + 32_000) { held[it] }); put("audioSeconds", 1.0); put("sampleRate", 16_000) }
                                    else -> {
                                        write("transcript.json", "application/json", """{"segments":[{"id":"1","startMs":0,"endMs":900,"text":"fake"}]}""".toByteArray())
                                        write("transcript.vtt", "text/vtt", "WEBVTT\n\n00:00:00.000 --> 00:00:00.900\nfake\n".toByteArray())
                                        put("segments", 1)
                                    }
                                }
                            }
                            channel.send(WorkerMessages.done(job.key, WorkerOutcome.Completed(summary, outputs)))
                        }
                    }
                }
            }
        }
    }
}
