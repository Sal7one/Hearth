package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.PcmFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

data class AudioCaptureDevice(val id: String, val name: String)

/** One capture owner, 16 queued 100 ms frames plus in-flight producer/emitter/consumer frames. */
class DesktopAudioCapture private constructor(private val deviceId: String?, private val lineFactory: ((AudioFormat) -> TargetDataLine)?) : Closeable {
    constructor(deviceId: String? = null) : this(deviceId?.takeIf(String::isNotBlank), null)
    internal constructor(lineFactory: (AudioFormat) -> TargetDataLine) : this(null, lineFactory)
    private val started = AtomicBoolean()
    private val stop = AtomicBoolean()
    private val line = AtomicReference<TargetDataLine?>()
    private val events = Channel<PcmFrame>(16)
    private val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val producer = AtomicReference<Thread?>()

    fun start(): Flow<PcmFrame> = flow {
        check(started.compareAndSet(false, true)) { "Microphone capture can only be started once" }
        val format = AudioFormat(16000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        try {
            val input = lineFactory?.invoke(format) ?: if (deviceId == null) AudioSystem.getLine(info) as TargetDataLine else {
                val devices = AudioSystem.getMixerInfo()
                val index = deviceId.toIntOrNull()
                require(index != null && index in devices.indices) { "The selected microphone is no longer available" }
                AudioSystem.getMixer(devices[index]).getLine(info) as TargetDataLine
            }
            line.set(input)
            input.open(format, 16000)
            if (stop.get()) throw CancellationException("Microphone capture cancelled")
            input.start()
            val worker = Thread({
                try {
                    while (!stop.get()) {
                        val buffer = ByteArray(3200)
                        val count = input.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            check(count % 2 == 0) { "Microphone returned an incomplete PCM16 sample" }
                            val result = events.trySend(PcmFrame(buffer.copyOf(count)))
                            if (result.isFailure && !stop.get()) error("Microphone queue overflow: recognition cannot keep up; use a smaller model")
                        } else if (!stop.get()) error("Microphone stopped producing PCM audio")
                    }
                    events.close()
                } catch (error: Throwable) {
                    if (stop.get()) events.close() else events.close(error)
                } finally {
                    input.close(); line.compareAndSet(input, null); done.complete(Unit)
                }
            }, "hearth-desktop-capture").apply { isDaemon = true }
            producer.set(worker)
            worker.start()
            for (frame in events) emit(frame)
        } catch (error: Throwable) {
            line.getAndSet(null)?.close()
            if (producer.get() == null) done.complete(Unit)
            throw error
        } finally {
            stop.set(true); line.getAndSet(null)?.close()
            // Closing the source wakes a blocking read; the collector still joins its producer.
            withContext(NonCancellable + Dispatchers.IO) { producer.get()?.join() }
            done.complete(Unit)
        }
    }.flowOn(Dispatchers.IO).buffer(0) // Rendezvous the dispatcher boundary; never add flowOn's default 64-frame queue.

    /** Stop recording normally; already queued frames remain available to recognition. */
    suspend fun finish() {
        stop.set(true); line.get()?.let { it.stop(); it.close() }
        if (started.get()) {
            done.await()
            withContext(NonCancellable + Dispatchers.IO) { producer.get()?.join() }
        } else { events.close(); done.complete(Unit) }
    }
    fun cancel() {
        stop.set(true); events.cancel(CancellationException("Microphone capture cancelled"))
        line.getAndSet(null)?.close()
        if (!started.get()) done.complete(Unit)
    }
    override fun close() = cancel()

    companion object {
        fun devices(): List<AudioCaptureDevice> = AudioSystem.getMixerInfo().mapIndexedNotNull { index, info ->
            val mixer = AudioSystem.getMixer(info)
            if (mixer.targetLineInfo.isEmpty()) null else AudioCaptureDevice(index.toString(), info.name)
        }
    }
}

object DesktopAudioFiles {
    /** Probe only the header; unsupported codecs go through the explicit OS decoder. */
    internal fun supports(path: Path): Boolean {
        require(Files.isRegularFile(path)) { "Audio file does not exist: $path" }
        Files.newInputStream(path).use {
            val header = it.readNBytes(12)
            if (header.size != 12 || String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return false
        }
        return try {
            val source = AudioSystem.getAudioFileFormat(path.toFile()).format
            source.encoding == AudioFormat.Encoding.PCM_SIGNED && source.sampleSizeInBits == 16 &&
                !source.isBigEndian && source.channels in 1..2 && source.sampleRate in 8000f..96000f &&
                AudioSystem.isConversionSupported(AudioFormat(16000f, 16, 1, true, false), source)
        } catch (_: javax.sound.sampled.UnsupportedAudioFileException) { false }
    }
    /** PCM16 mono/stereo RIFF WAV only. JavaSound performs the explicit 16 kHz mono conversion. */
    fun frames(path: String): Flow<PcmFrame> = flow {
        val file = Path.of(path).toFile()
        require(file.isFile) { "Audio file does not exist: $path" }
        file.inputStream().use {
            val header = it.readNBytes(12)
            require(header.size == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Import PCM16 WAV audio; this format is unsupported" }
        }
        AudioSystem.getAudioInputStream(file).use { original ->
            val source = original.format
            require(source.encoding == AudioFormat.Encoding.PCM_SIGNED && source.sampleSizeInBits == 16 &&
                !source.isBigEndian && source.channels in 1..2 && source.sampleRate in 8000f..96000f) {
                "Import PCM16 mono/stereo WAV at 8–96 kHz"
            }
            val target = AudioFormat(16000f, 16, 1, true, false)
            require(AudioSystem.isConversionSupported(target, source)) { "JavaSound cannot convert this WAV to 16 kHz mono" }
            AudioSystem.getAudioInputStream(target, original).use { input ->
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val buffer = ByteArray(3200)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(count % 2 == 0) { "WAV contains a truncated PCM16 sample" }
                    total += count
                    require(total <= 230_400_000L) { "WAV exceeds the two-hour import limit" }
                    emit(PcmFrame(buffer.copyOf(count)))
                }
                require(total > 0) { "WAV has no audio frames" }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Explicit playback; synthesis itself never claims to have played its output. */
class DesktopAudioPlayback : Closeable {
    private val line = AtomicReference<SourceDataLine?>()
    private val active = AtomicBoolean()
    suspend fun play(path: String) {
        check(active.compareAndSet(false, true)) { "Audio is already playing" }
        try {
            joinedBlocking(::cancel) {
                AudioSystem.getAudioInputStream(Path.of(path).toFile()).use { input ->
                    val output = AudioSystem.getSourceDataLine(input.format)
                    line.set(output)
                    try {
                        output.open(input.format); output.start()
                        val buffer = ByteArray(8192)
                        while (active.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            var offset = 0
                            while (offset < count && active.get()) {
                                val written = output.write(buffer, offset, count - offset)
                                check(written > 0) { "Audio output stopped accepting samples" }
                                offset += written
                            }
                        }
                        if (active.get()) output.drain()
                    } finally { line.compareAndSet(output, null); output.close() }
                }
            }
        } finally { active.set(false); line.getAndSet(null)?.close() }
    }
    fun cancel() { active.set(false); line.getAndSet(null)?.let { it.stop(); it.flush(); it.close() } }
    override fun close() = cancel()
}

internal class WaveWriter(private val destination: Path, private val rate: Int) : Closeable {
    private val temporary = destination.resolveSibling(destination.fileName.toString() + ".part")
    private val output: RandomAccessFile
    private var samples = 0L
    private var published = false
    init { Files.createDirectories(destination.parent); output = RandomAccessFile(temporary.toFile(), "rw"); output.setLength(0); output.write(ByteArray(44)) }
    fun append(values: FloatArray) {
        require(values.isNotEmpty() && values.all(Float::isFinite)) { "Voice returned invalid audio" }
        samples += values.size
        require(samples <= rate.toLong() * 7200) { "Generated audio exceeds two hours" }
        val data = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        output.write(data.array())
    }
    fun publish(): Path {
        require(samples > 0) { "Voice produced no audio" }
        val bytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes.toInt())
        output.seek(0); output.write(header.array()); output.close()
        Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        published = true
        return destination
    }
    override fun close() { output.close(); if (!published) Files.deleteIfExists(temporary) }
}
