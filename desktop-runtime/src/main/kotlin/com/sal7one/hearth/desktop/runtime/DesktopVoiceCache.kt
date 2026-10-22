package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.GeneratedAudio
import com.sal7one.hearth.tts.ArticleReader
import com.sal7one.hearth.tts.ArticleSpeech
import com.sal7one.hearth.tts.SpeechEngine
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.VoicePreference
import com.sal7one.hearth.tts.supertonic.SupertonicCatalog
import com.sal7one.hearth.tts.supertonic.SupertonicSpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** One resident, SDK-verified voice owner; successful captions keep its sessions and styles warm. */
internal class DesktopVoiceCache(
    private val directory: Path,
    private val engineId: String = "supertonic-local",
    private val sampleRate: Int = 44_100,
    private val defaultVoice: String = "F1",
    private val assetNames: List<String> = SupertonicCatalog.assets.map { it.filename },
    private val prepare: ((Path) -> Unit)? = null,
    private val engineFactory: (Path, Int) -> SpeechEngine = { path, _ -> SupertonicSpeechEngine(path.toFile()) },
) : AutoCloseable {
    private val voiceGate = Mutex()
    private val lifecycle = Any()
    private var closed = false // Guarded by lifecycle.
    private var active: Job? = null // Guarded by lifecycle; no lock is held while cancelling/joining it.
    private var cached: Resident? = null // Guarded by voiceGate.

    private data class AssetStamp(val name: String, val path: Path?, val bytes: Long = 0,
        val modified: FileTime? = null, val created: FileTime? = null, val fileKey: String? = null)
    private data class PackageKey(val root: Path, val files: List<AssetStamp>, val threads: Int)
    private class Resident(val key: PackageKey, val reader: ArticleReader)

    suspend fun read(text: String, selection: EngineSelection, language: String, voiceId: String): GeneratedAudio = voiceGate.withLock {
        val owner = currentCoroutineContext().job
        synchronized(lifecycle) {
            check(!closed) { "Desktop voice cache is closed" }
            active = owner
        }
        var output: Path? = null
        try {
            require(selection.engineId == engineId) { "Desktop voice cache supports $engineId only" }
            require(text.isNotBlank()) { "Read aloud needs text" }
            require(selection.options["acceleration"] in setOf(null, "cpu")) { "Local desktop voices currently support CPU acceleration only" }
            // Speed/steps are Supertonic controls. Other local engines require normal speed and
            // do not consume quality steps; ignore stale setup values instead of failing at synth.
            val supertonic = engineId == "supertonic-local"
            val options = SpeechOptions(
                rate = if (supertonic) selection.options["speed"]?.toFloatOrNull() ?: 1f else 1f,
                qualitySteps = if (supertonic) selection.options["steps"]?.toIntOrNull() ?: 5 else 5,
            )
            val threadCount = (selection.options["threads"]?.toIntOrNull() ?: 2).also { require(it in 1..8) { "Voice threads must be 1–8" } }
            val key = withContext(Dispatchers.IO) {
                val root = Path.of(selection.modelPath)
                prepare?.invoke(root)
                fingerprint(root, threadCount)
            }
            if (cached?.key != key) {
                retire()
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val engine = engineFactory(key.root, key.threads)
                    val reader = try { ArticleReader(engine) }
                    catch (failure: Throwable) {
                        try { engine.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                    // Publish the owner before crossing a cancellable dispatcher-return boundary.
                    cached = Resident(key, reader)
                }
            }
            val reader = checkNotNull(cached).reader
            withContext(Dispatchers.IO) {
                val target = outputFile(directory)
                output = target
                WaveWriter(target, sampleRate).use { wave ->
                    reader.read(text, language, VoicePreference(id = voiceId.ifBlank { defaultVoice }), options).collect { event ->
                        if (event is ArticleSpeech.Audio) {
                            check(event.pcm.sampleRate == sampleRate) { "The local voice returned an unexpected sample rate" }
                            wave.append(event.pcm.samples)
                        }
                    }
                    wave.publish()
                }
                GeneratedAudio(target.toString(), text = text)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { retire() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { output?.let { Files.deleteIfExists(it) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        } finally {
            synchronized(lifecycle) { if (active === owner) active = null }
        }
    }

    /** Missing unused styles remain valid metadata; the SDK verifies core and the selected style before inference. */
    private suspend fun fingerprint(path: Path, threads: Int): PackageKey {
        val root = path.toRealPath()
        require(Files.isDirectory(root)) { "Choose the local voice model directory" }
        val files = assetNames.map { filename ->
            currentCoroutineContext().ensureActive()
            try {
                val file = root.resolve(filename).toRealPath()
                val stat = Files.readAttributes(file, BasicFileAttributes::class.java)
                require(stat.isRegularFile) { "Voice component is not a regular file: $filename" }
                AssetStamp(filename, file, stat.size(), stat.lastModifiedTime(), stat.creationTime(), stat.fileKey()?.toString())
            } catch (_: NoSuchFileException) { AssetStamp(filename, null) }
        }
        return PackageKey(root, files, threads)
    }

    private suspend fun retire() = withContext(NonCancellable) {
        val previous = cached
        cached = null
        previous?.reader?.closeAndJoin()
    }

    /** Revokes new reads, cancels/joins an active owner, then joins native teardown exactly once. */
    suspend fun closeAndJoin() {
        val reading = synchronized(lifecycle) { closed = true; active }
        reading?.cancel(CancellationException("Desktop voice cache closed"))
        withContext(NonCancellable) {
            reading?.join()
            voiceGate.withLock { retire() }
        }
    }

    /** Blocking lifecycle boundary; desktop engines call this on IO after cancelling pipeline jobs. */
    override fun close() { runBlocking { closeAndJoin() } }

}
