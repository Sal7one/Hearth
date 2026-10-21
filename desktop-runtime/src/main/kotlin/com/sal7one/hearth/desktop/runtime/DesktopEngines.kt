package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.desktop.DesktopBackendSpeechNative
import com.sal7one.common_jni.desktop.DesktopQwenNative
import com.sal7one.common_jni.desktop.DesktopWhisperNative
import com.sal7one.common_jni.translation.Ct2Native
import com.sal7one.common_jni.translation.LocalTranslationNative
import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.media.DesktopMediaDecoder
import com.sal7one.hearth.tts.supertonic.SupertonicCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Independent desktop adapters. Native handles are private to this owner, never shared with Android. */
class DesktopEngines(
    credentials: CredentialResolver,
    val appDataDirectory: Path = defaultDataDirectory(),
) : PipelineEngines, AutoCloseable {
    private val cloud = DesktopCloud(credentials, appDataDirectory)
    private val voices = DesktopVoiceCache(appDataDirectory)
    private val translationGate = Mutex()
    private val ct2Gate = Mutex()
    private val lifecycle = Any()
    private val closed = AtomicBoolean()
    private var translationHandle = 0L
    private var translationKey = ""
    private var ct2Engine: DesktopCt2TranslationEngine? = null
    private var ct2Key = ""
    init { DesktopNativeLibraries.configure(appDataDirectory) }

    override fun capabilities(): List<EngineDescriptor> {
        fun local(id: String, name: String, stage: PipelineStage, library: String, languages: Set<String> = emptySet(), streaming: Boolean = false): EngineDescriptor {
            var error = DesktopNativeLibraries.readiness(library)
            val backendLibrary = when (id) { "transcribepsy-local" -> "hearth_transcribepsy"; else -> null }
            if (backendLibrary != null && error == null) error = DesktopNativeLibraries.readiness(backendLibrary)
            var acceleration = setOf("cpu")
            if (error == null) {
                runCatching {
                    val metal = when (id) {
                        "whisper-local" -> {
                            check(DesktopWhisperNative.protocolVersion() == 2) { "Desktop Whisper protocol mismatch; rebuild the native bundle" }
                            DesktopWhisperNative.metalAvailable()
                        }
                        "llama-translation-local" -> {
                            check(LocalTranslationNative.promptProtocolVersion() == 4) { "Desktop translation protocol mismatch; rebuild the native bundle" }
                            LocalTranslationNative.metalAvailable()
                        }
                        QwenSpeechModel.ENGINE_ID -> {
                            check(DesktopQwenNative.protocolVersion() == QwenSpeechModel.PROTOCOL_VERSION) { "Desktop Qwen3-ASR protocol mismatch; rebuild the native bundle" }
                            false
                        }
                        "marian-local" -> {
                            check(Ct2Native.protocolVersion() == DesktopCt2TranslationEngine.PROTOCOL_VERSION) { "Desktop CTranslate2 protocol mismatch; rebuild the native bundle" }
                            false
                        }
                        "transcribepsy-local" -> {
                            check(DesktopBackendSpeechNative.protocolVersion() == DesktopBackendSpeechEngine.PROTOCOL_VERSION) { "Desktop speech backend protocol mismatch; rebuild the native bundle" }
                            false
                        }
                        else -> false
                    }
                    if (metal) acceleration = setOf("cpu", "metal")
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
            return EngineDescriptor(id, name, stage, EngineKind.LOCAL, languages, streaming,
                acceleration = acceleration, available = error == null, unavailableReason = error, requiresModel = true)
        }
        return listOf(
            local("whisper-local", "Whisper.cpp", PipelineStage.STT, "hearth_desktop_whisper", streaming = true),
            local(QwenSpeechModel.ENGINE_ID, "Qwen3-ASR 0.6B", PipelineStage.STT, QwenSpeechModel.LIBRARY, QwenSpeechModel.languages),
            local("transcribepsy-local", "TranscribePsy 250M", PipelineStage.STT, "hearth_desktop_backend_speech", TRANSCRIBEPSY_LANGUAGES),
            local("llama-translation-local", "Local GGUF translation", PipelineStage.TRANSLATION, "transiber_translation"),
            local("marian-local", "CTranslate2 · Marian/OPUS", PipelineStage.TRANSLATION, "hearth_desktop_ct2"),
            local("supertonic-local", "Supertonic 3", PipelineStage.TTS, "hearth_supertonic_jni", SupertonicCatalog.languages),
            EngineDescriptor("openai-stt", "OpenAI-compatible transcription", PipelineStage.STT, EngineKind.CLOUD,
                streaming = true, requiresCredential = true, defaultEndpoint = "https://api.openai.com/v1/audio/transcriptions", defaultModel = "gpt-4o-mini-transcribe"),
            EngineDescriptor("openai-translation", "OpenAI-compatible translation", PipelineStage.TRANSLATION, EngineKind.CLOUD,
                requiresCredential = true, defaultEndpoint = "https://api.openai.com/v1/chat/completions", defaultModel = "gpt-4.1-mini"),
            EngineDescriptor("openai-tts", "OpenAI-compatible speech", PipelineStage.TTS, EngineKind.CLOUD,
                requiresCredential = true, defaultEndpoint = "https://api.openai.com/v1/audio/speech", defaultModel = "gpt-4o-mini-tts"),
            EngineDescriptor("system-tts", "Installed system voices", PipelineStage.TTS, EngineKind.SYSTEM,
                available = DesktopSystemSpeech.available, unavailableReason = if (DesktopSystemSpeech.available) null else "System speech is available on Windows and macOS"),
        )
    }

    override suspend fun recognize(input: PipelineInput, selection: EngineSelection, sourceLanguage: String, emit: suspend (SpeechSegment) -> Unit) {
        check(!closed.get()) { "Desktop engines are closed" }
        require(selection.engineId in setOf("whisper-local", QwenSpeechModel.ENGINE_ID, "transcribepsy-local", "openai-stt")) { "Unknown speech engine: ${selection.engineId}" }
        var decoded: Path? = null
        val frames: Flow<PcmFrame> = when (input) {
            is PipelineInput.AudioStream -> input.frames
            is PipelineInput.AudioFile -> {
                val path = Path.of(input.path)
                if (!withContext(Dispatchers.IO) { DesktopAudioFiles.supports(path) }) {
                    decoded = DesktopMediaDecoder.decode(path, appDataDirectory.resolve("decoded-audio"))
                    DesktopAudioFiles.frames(decoded.toString())
                } else DesktopAudioFiles.frames(input.path)
            }
            is PipelineInput.Text -> error("Speech recognition needs an audio file or microphone")
        }
        val handle = AtomicLong()
        val qwenEngine = AtomicReference<DesktopQwenSpeechEngine?>(null)
        val backendEngine = AtomicReference<DesktopBackendSpeechEngine?>(null)
        var offsetSamples = 0L
        var sequence = 0
        var recognized = false
        try {
            if (selection.engineId == "whisper-local") {
                val path = verifiedModel(selection)
                check(DesktopWhisperNative.protocolVersion() == 2) { "Desktop Whisper protocol mismatch" }
                val metal = metal(selection)
                val owner = currentCoroutineContext()
                joinedBlocking({ handle.get().takeIf { it != 0L }?.let(DesktopWhisperNative::cancel) }) {
                    owner.ensureActive()
                    handle.set(DesktopWhisperNative.create(path.toString().toByteArray(), sourceLanguage.toByteArray(), threads(selection), metal))
                    check(handle.get() != 0L) { "Whisper returned an invalid model handle" }
                }
            } else if (selection.engineId == QwenSpeechModel.ENGINE_ID) {
                val directory = verifiedQwenModel(selection)
                val language = QwenSpeechModel.language(sourceLanguage)
                val threadCount = threads(selection)
                val owner = currentCoroutineContext()
                joinedBlocking({ qwenEngine.get()?.cancel() }) {
                    owner.ensureActive()
                    val opened = DesktopQwenSpeechEngine.open(directory, language, threadCount)
                    if (!qwenEngine.compareAndSet(null, opened)) {
                        opened.close()
                        error("Qwen3-ASR engine was already published")
                    }
                }
            } else if (selection.engineId == "transcribepsy-local") {
                val path = verifiedModel(selection)
                val owner = currentCoroutineContext()
                joinedBlocking({ backendEngine.get()?.cancel() }) {
                    owner.ensureActive()
                    val opened = DesktopBackendSpeechEngine.open("hearth_transcribepsy", "transcribepsy", path, sourceLanguage, threads(selection))
                    if (!backendEngine.compareAndSet(null, opened)) {
                        opened.close()
                        error("Speech backend engine was already published")
                    }
                }
            }
            val live = input is PipelineInput.AudioStream
            val spokenWindow = selection.engineId == QwenSpeechModel.ENGINE_ID || selection.engineId == "transcribepsy-local"
            val windowMs = selection.options["windowMs"]?.toIntOrNull()
                ?: if (spokenWindow) 15000 else if (live) 4000 else 20000
            require(windowMs in 1000..30000) { "Speech window must be 1000–30000 ms" }
            val windowBytes = windowMs * 32
            val buffered = ByteArrayOutputStream(windowBytes)
            var speechSamples = 0
            var silenceSamples = 0
            suspend fun transcribe() {
                val bytes = buffered.toByteArray(); buffered.reset()
                if (bytes.isEmpty()) return
                currentCoroutineContext().ensureActive()
                val observedSpeech = speechSamples
                speechSamples = 0; silenceSamples = 0
                if (live && observedSpeech == 0) { offsetSamples += bytes.size / 2; return }
                val text: String
                var language: String? = sourceLanguage.takeUnless { it == "auto" }
                val owner = currentCoroutineContext()
                if (selection.engineId == "openai-stt") {
                    text = cloud.recognize(bytes, selection, sourceLanguage)
                } else {
                    val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
                    if (selection.engineId == QwenSpeechModel.ENGINE_ID) {
                        val engine = checkNotNull(qwenEngine.get()) { "Qwen3-ASR is not open" }
                        text = joinedBlocking({ engine.cancel() }) { owner.ensureActive(); engine.transcribe(samples) }
                        language = QwenSpeechModel.detected(engine.language()) ?: language
                    } else if (selection.engineId == "transcribepsy-local") {
                        val engine = checkNotNull(backendEngine.get()) { "Speech backend is not open" }
                        text = joinedBlocking({ engine.cancel() }) { owner.ensureActive(); engine.transcribe(samples) }
                        language = engine.language().ifBlank { language }
                    } else {
                        text = joinedBlocking({ DesktopWhisperNative.cancel(handle.get()) }) {
                            owner.ensureActive(); DesktopWhisperNative.transcribe(handle.get(), samples).toString(Charsets.UTF_8).trim()
                        }
                        language = DesktopWhisperNative.language(handle.get()).toString(Charsets.UTF_8).ifBlank { language }
                    }
                }
                val end = offsetSamples + bytes.size / 2
                if (text.isNotBlank()) {
                    emit(SpeechSegment("speech-${sequence++}", text, true, offsetSamples / 16, end / 16, language))
                    recognized = true
                }
                offsetSamples = end
            }
            // Utterance-windowed recognition, not a claim of token-by-token streaming.
            frames.collect { frame ->
                var cursor = 0
                while (cursor < frame.bytes.size) {
                    val count = minOf(frame.bytes.size - cursor, windowBytes - buffered.size())
                    buffered.write(frame.bytes, cursor, count); cursor += count
                    if (live) {
                        var energy = 0.0
                        val samples = ByteBuffer.wrap(frame.bytes, cursor - count, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (samples.hasRemaining()) { val value = samples.get() / 32768.0; energy += value * value }
                        if (kotlin.math.sqrt(energy / (count / 2)) >= 0.008) { speechSamples += count / 2; silenceSamples = 0 }
                        else silenceSamples += count / 2
                    }
                    if (buffered.size() == windowBytes || live && speechSamples >= 4000 && silenceSamples >= 9600) transcribe()
                }
            }
            transcribe()
            check(recognized) { "No speech was recognized in the supplied audio" }
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        qwenEngine.getAndSet(null)?.close()
                        backendEngine.getAndSet(null)?.close()
                    } finally {
                        handle.getAndSet(0).takeIf { it != 0L }?.let(DesktopWhisperNative::destroy)
                    }
                }
            } finally {
                decoded?.let { Files.deleteIfExists(it) }
            }
        }
    }

    override suspend fun translate(text: String, selection: EngineSelection, sourceLanguage: String, targetLanguage: String): String {
        check(!closed.get()) { "Desktop engines are closed" }
        require(text.isNotBlank() && text.length <= TextChunks.MAX_TEXT_CHARACTERS) { "Translation accepts 1–32000 characters" }
        if (selection.engineId == "openai-translation") return cloud.translate(text, selection, sourceLanguage, targetLanguage)
        if (selection.engineId == "marian-local") return translateCt2(text, selection, sourceLanguage, targetLanguage)
        require(selection.engineId == "llama-translation-local") { "Unknown translation engine: ${selection.engineId}" }
        return translationGate.withLock {
            val path = Path.of(selection.modelPath).toAbsolutePath().normalize()
            val mode = when (selection.options["promptMode"] ?: "default") { "default" -> 0; "raw" -> 1; "euro" -> 2; "riva" -> 3; else -> error("Unknown local translation prompt mode") }
            val hash = selection.options["sha256"].orEmpty()
            val metal = metal(selection)
            val threads = threads(selection)
            val key = "$path|$hash|$mode|$metal|$threads|${Files.size(path)}|${Files.getLastModifiedTime(path)}"
            try {
                if (synchronized(lifecycle) { translationKey != key || translationHandle == 0L }) {
                    verifiedModel(selection)
                    withContext(Dispatchers.IO) {
                        synchronized(lifecycle) {
                            check(!closed.get()) { "Desktop engines are closed" }
                            if (translationHandle != 0L) LocalTranslationNative.destroy(translationHandle)
                            translationHandle = 0; translationKey = ""
                        }
                    }
                    val owner = currentCoroutineContext()
                    joinedBlocking({ synchronized(lifecycle) { translationHandle.takeIf { it != 0L }?.let(LocalTranslationNative::cancel) } }) {
                        owner.ensureActive()
                        check(LocalTranslationNative.promptProtocolVersion() == 4) { "Local translation native prompt protocol mismatch" }
                        val created = LocalTranslationNative.create(path.toString().toByteArray(), mode, threads, if (metal) 99 else 0)
                        synchronized(lifecycle) {
                            if (closed.get()) { LocalTranslationNative.destroy(created); error("Desktop engines are closed") }
                            translationHandle = created; translationKey = key
                        }
                    }
                }
                val result = mutableListOf<String>()
                for (part in TextChunks.split(text, 500)) {
                    currentCoroutineContext().ensureActive()
                    val instruction = DesktopTranslationPrompts.prompt(part, sourceLanguage, targetLanguage,
                        selection.options["family"] ?: when (mode) { 1 -> "milmmt-46"; 2 -> "eurollm"; 3 -> "riva-translate"; else -> "hy-mt1.5" })
                    val handle = synchronized(lifecycle) { translationHandle }
                    val owner = currentCoroutineContext()
                    val output = joinedBlocking({ LocalTranslationNative.cancel(handle) }) {
                        owner.ensureActive(); LocalTranslationNative.translate(handle, instruction.toByteArray()).toString(Charsets.UTF_8).trim()
                    }
                    check(output.isNotBlank()) { "Translation model returned empty text" }
                    result += output
                }
                result.joinToString("\n")
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { clearTranslation() }
                throw error
            }
        }
    }

    private suspend fun translateCt2(text: String, selection: EngineSelection, sourceLanguage: String, targetLanguage: String): String = ct2Gate.withLock {
        val root = Path.of(selection.modelPath).toRealPath()
        require(Files.isDirectory(root)) { "Select a complete CTranslate2 model directory" }
        val mode = selection.options["profile"]?.toIntOrNull() ?: error("CTranslate2 tokenizer mode is required")
        require(mode in 0..5) { "Unsupported CTranslate2 tokenizer mode" }
        val sources = selection.options["sources"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val targets = selection.options["targets"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        require(sources.isEmpty() || sourceLanguage in sources) { "This CTranslate2 model does not support $sourceLanguage → $targetLanguage" }
        require(targets.isEmpty() || targetLanguage in targets) { "This CTranslate2 model does not support $sourceLanguage → $targetLanguage" }
        val key = "$root|$mode|${Files.getLastModifiedTime(root.resolve("model.bin"))}"
        if (synchronized(lifecycle) { ct2Key != key || ct2Engine == null }) {
            verifiedCt2Model(root, selection)
            withContext(Dispatchers.IO) {
                synchronized(lifecycle) {
                    check(!closed.get()) { "Desktop engines are closed" }
                    ct2Engine?.close()
                    ct2Engine = DesktopCt2TranslationEngine.open(root, mode)
                    ct2Key = key
                }
            }
        }
        val engine = synchronized(lifecycle) { ct2Engine } ?: error("CTranslate2 engine is not open")
        try {
            val result = mutableListOf<String>()
            for (part in TextChunks.split(text, 500)) {
                currentCoroutineContext().ensureActive()
                val owner = currentCoroutineContext()
                result += joinedBlocking({ engine.cancel() }) { owner.ensureActive(); engine.translate(part, targetLanguage) }
            }
            result.joinToString("\n")
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { clearCt2() }
            throw error
        }
    }

    private suspend fun verifiedCt2Model(root: Path, selection: EngineSelection) = withContext(Dispatchers.IO) {
        val pins = selection.options.filterKeys { it.startsWith("sha256.") }
        require(pins.isNotEmpty()) { "A pinned SHA-256 is required for every CTranslate2 component" }
        for ((key, expected) in pins) {
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "A pinned SHA-256 is required for every CTranslate2 component" }
            val relative = key.removePrefix("sha256.")
            require(relative.isNotBlank() && !relative.startsWith("/") && relative.split('/').none { it == ".." || it.isEmpty() }) { "Invalid CTranslate2 component: $relative" }
            val file = root.resolve(relative).normalize()
            require(file.startsWith(root)) { "CTranslate2 component escapes its directory: $relative" }
            check(Files.isRegularFile(file) && Files.size(file) > 0) { "Missing CTranslate2 component: $relative" }
            currentCoroutineContext().ensureActive()
            check(sha256(file) == expected) { "CTranslate2 component checksum mismatch: $relative" }
        }
        root
    }

    override suspend fun synthesize(text: String, selection: EngineSelection, language: String, voiceId: String): GeneratedAudio {
        check(!closed.get()) { "Desktop engines are closed" }
        require(text.isNotBlank()) { "Read aloud needs text" }
        if (selection.engineId == "openai-tts") return cloud.synthesize(text, selection, voiceId)
        if (selection.engineId == "system-tts") return DesktopSystemSpeech.synthesize(text, language, voiceId, appDataDirectory)
        require(selection.engineId == "supertonic-local") { "Unknown voice engine: ${selection.engineId}" }
        require(!metal(selection)) { "Supertonic desktop currently supports CPU acceleration only" }
        return voices.read(text, selection, language, voiceId)
    }

    private fun clearTranslation() = synchronized(lifecycle) {
        val id = translationHandle; translationHandle = 0; translationKey = ""
        if (id != 0L) LocalTranslationNative.destroy(id)
    }
    private fun clearCt2() = synchronized(lifecycle) {
        val engine = ct2Engine; ct2Engine = null; ct2Key = ""
        engine?.close()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) try { clearTranslation(); clearCt2() } finally { voices.close() }
    }

    private suspend fun verifiedModel(selection: EngineSelection): Path = withContext(Dispatchers.IO) {
        val path = Path.of(selection.modelPath).toRealPath()
        require(Files.isRegularFile(path) && Files.size(path) > 0) { "Select a complete model file" }
        val expected = selection.options["sha256"].orEmpty().lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "A pinned SHA-256 is required for this local model" }
        currentCoroutineContext().ensureActive()
        check(sha256(path) == expected) { "Local model checksum mismatch: ${path.fileName}" }
        currentCoroutineContext().ensureActive()
        path
    }

    /** Qwen3-ASR ships as a directory; the engine owns the six reviewed file pins. */
    private suspend fun verifiedQwenModel(selection: EngineSelection): Path = withContext(Dispatchers.IO) {
        val root = Path.of(selection.modelPath).toRealPath()
        val context = currentCoroutineContext()
        QwenSpeechModel.verify(root) { context.ensureActive() }
        root
    }

    private fun threads(selection: EngineSelection) = (selection.options["threads"]?.toIntOrNull() ?: minOf(4, Runtime.getRuntime().availableProcessors())).also { require(it in 1..32) { "Threads must be 1–32" } }
    private fun metal(selection: EngineSelection): Boolean = when (selection.options["acceleration"] ?: "cpu") {
        "cpu" -> false
        "metal" -> true
        else -> error("Unsupported desktop acceleration: ${selection.options["acceleration"]}")
    }
}

internal fun outputFile(directory: Path): Path = directory.resolve("generated-audio").also { Files.createDirectories(it) }.resolve("${UUID.randomUUID()}.wav")

private val TRANSCRIBEPSY_LANGUAGES = setOf("en", "nl", "fr", "de", "it", "pl", "pt", "es")
