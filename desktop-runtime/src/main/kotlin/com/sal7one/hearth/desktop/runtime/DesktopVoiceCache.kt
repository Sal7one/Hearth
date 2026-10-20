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

/** One resident, SDK-verified Supertonic owner; successful captions keep its sessions and styles warm. */
internal class DesktopVoiceCache(
    private val directory: Path,
    private val engineFactory: (Path) -> SpeechEngine = { SupertonicSpeechEngine(it.toFile()) },
) : AutoCloseable {
    private val voiceGate = Mutex()
    private val lifecycle = Any()
    private var closed = false // Guarded by lifecycle.
    private var active: Job? = null // Guarded by lifecycle; no lock is held while cancelling/joining it.
    private var cached: Resident? = null // Guarded by voiceGate.

    private data class AssetStamp(val name: String, val path: Path?, val bytes: Long = 0,
        val modified: FileTime? = null, val created: FileTime? = null, val fileKey: String? = null)
    private data class PackageKey(val root: Path, val files: List<AssetStamp>)
    private class Resident(val key: PackageKey, val reader: ArticleReader)

    suspend fun read(text: String, selection: EngineSelection, language: String, voiceId: String): GeneratedAudio = voiceGate.withLock {
        val owner = currentCoroutineContext().job
        synchronized(lifecycle) {
            check(!closed) { "Desktop voice cache is closed" }
            active = owner
        }
        var output: Path? = null
        try {
            require(selection.engineId == "supertonic-local") { "Desktop voice cache supports Supertonic only" }
            require(text.isNotBlank()) { "Read aloud needs text" }
            require(selection.options["acceleration"] in setOf(null, "cpu")) { "Supertonic desktop currently supports CPU acceleration only" }
            val options = SpeechOptions(
                rate = selection.options["speed"]?.toFloatOrNull() ?: 1f,
                qualitySteps = selection.options["steps"]?.toIntOrNull() ?: 5,
            )
            val key = withContext(Dispatchers.IO) { fingerprint(Path.of(selection.modelPath)) }
            if (cached?.key != key) {
                retire()
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val engine = engineFactory(key.root)
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
                WaveWriter(target, SAMPLE_RATE).use { wave ->
                    reader.read(text, language, VoicePreference(id = voiceId.ifBlank { "F1" }), options).collect { event ->
                        if (event is ArticleSpeech.Audio) {
                            check(event.pcm.sampleRate == SAMPLE_RATE) { "Supertonic returned an unexpected sample rate" }
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
    private suspend fun fingerprint(path: Path): PackageKey {
        val root = path.toRealPath()
        require(Files.isDirectory(root)) { "Choose the Supertonic model directory" }
        val files = SupertonicCatalog.assets.map { asset ->
            currentCoroutineContext().ensureActive()
            try {
                val file = root.resolve(asset.filename).toRealPath()
                val stat = Files.readAttributes(file, BasicFileAttributes::class.java)
                require(stat.isRegularFile) { "Supertonic component is not a regular file: ${asset.filename}" }
                AssetStamp(asset.filename, file, stat.size(), stat.lastModifiedTime(), stat.creationTime(), stat.fileKey()?.toString())
            } catch (_: NoSuchFileException) { AssetStamp(asset.filename, null) }
        }
        return PackageKey(root, files)
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

    private companion object { const val SAMPLE_RATE = 44_100 }
}
