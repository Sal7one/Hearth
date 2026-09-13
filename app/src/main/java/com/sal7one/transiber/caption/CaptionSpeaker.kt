package com.sal7one.transiber.caption

import com.sal7one.transiber.voice.OfflineVoicePolicy
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.sal7one.common_jni.tts.TtsConfig
import com.sal7one.common_jni.tts.TtsEngineType
import com.sal7one.common_jni.tts.TtsKit
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.byok.CloudTtsSpeaker
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speaks finalized caption lines aloud. Three backends behind one queue:
 *
 *  • SYSTEM  — the device's TTS engine (offline wherever a voice is
 *    installed; instant, no model import needed).
 *  • NATIVE  — this app's on-device neural TTS (TtsKit, ONNX voice model
 *    imported from the catalogue; fully offline).
 *  • CLOUD   — NETWORK CODE (BYOK, play distribution only) → byok/.
 *
 * Offline-first note: SYSTEM and NATIVE never touch the network; CLOUD
 * is constructed only when the user picks it in the play build.
 */
interface CaptionSpeaker {
    /** Enqueues one utterance; returns false when the voice can't speak [languageTag]. */
    fun speak(text: String, languageTag: String): Boolean

    fun stop()

    fun release()
}

object CaptionSpeakerFactory {

    /** SYSTEM works everywhere; NATIVE needs an engine AND a voice model; CLOUD needs play + key. */
    fun isAvailable(context: Context, choice: CaptionSpeakerChoice): Boolean = when (choice) {
        CaptionSpeakerChoice.SYSTEM -> true
        CaptionSpeakerChoice.NATIVE ->
            // An imported model alone is not enough — a TTS engine binary
            // must also be present, or lines would be silently dropped.
            TtsKit.getAvailableEngines().isNotEmpty() && vitsBundlePath(context) != null
        CaptionSpeakerChoice.CLOUD ->
            ByokPolicy.FEATURE_BYOK && com.sal7one.transiber.byok.ApiKeyStore.hasOpenAiKey(context)
    }

    fun create(context: Context, choice: CaptionSpeakerChoice): CaptionSpeaker = when (choice) {
        CaptionSpeakerChoice.SYSTEM -> SystemTtsSpeaker(context)
        CaptionSpeakerChoice.NATIVE -> NativeNeuralSpeaker(context)
        CaptionSpeakerChoice.CLOUD -> CloudTtsSpeaker(
            apiKey = com.sal7one.transiber.byok.ApiKeyStore.getOpenAiKey(context),
            baseUrl = com.sal7one.transiber.byok.CloudConfigStore.baseUrl(context),
            model = com.sal7one.transiber.byok.CloudConfigStore.ttsModel(context),
            voice = com.sal7one.transiber.byok.CloudConfigStore.ttsVoice(context),
        )
    }

    /**
     * A sherpa-style VITS bundle folder (model.onnx + tokens.txt) the neural
     * engine can run, or null. Scans the app's model directories.
     */
    fun vitsBundlePath(context: Context): String? {
        val app = context.applicationContext
        val dirs = listOfNotNull(
            java.io.File(app.filesDir, "models"),
            app.filesDir,
            app.getExternalFilesDir(null),
            java.io.File(app.getExternalFilesDir(null), "models"),
        )
        return dirs.firstNotNullOfOrNull { dir ->
            dir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .firstOrNull { sub ->
                    java.io.File(sub, "model.onnx").exists() &&
                        java.io.File(sub, "tokens.txt").exists()
                }
                ?.absolutePath
        }
    }
}

/** Device TTS: instant, offline with installed voices, any language. */
class SystemTtsSpeaker(context: Context) : CaptionSpeaker {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    override fun speak(text: String, languageTag: String): Boolean {
        if (!ready || text.isBlank()) return false
        val locale = Locale.forLanguageTag(languageTag)
        val voices = tts.voices.orEmpty()
        val selected = OfflineVoicePolicy.select(voices.map {
            OfflineVoicePolicy.Candidate(it.name, it.locale, it.isNetworkConnectionRequired, it.quality)
        }, locale) ?: return false
        val voice = voices.first { it.name == selected }
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        return tts.speak(text, TextToSpeech.QUEUE_ADD, null, "caption-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    override fun stop() {
        tts.stop()
    }

    override fun release() {
        tts.stop()
        tts.shutdown()
    }
}

/**
 * On-device neural TTS via TtsKit (Kokoro / sherpa-onnx). Synthesis is
 * offloaded; utterances play strictly in order through one AudioTrack.
 */
class NativeNeuralSpeaker(private val context: Context) : CaptionSpeaker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var track: AudioTrack? = null
    private val queue = ArrayDeque<Pair<String, String>>()
    private var synthesizing = false

    override fun speak(text: String, languageTag: String): Boolean {
        if (text.isBlank()) return false
        synchronized(queue) { queue.addLast(text to languageTag) }
        drain()
        return true
    }

    private fun drain() {
        val shouldRun = synchronized(queue) {
            if (synthesizing || queue.isEmpty()) false else {
                synthesizing = true
                true
            }
        }
        if (!shouldRun) return
        scope.launch {
            try {
                while (true) {
                    val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    synthesizeOnce(next.first)
                }
            } finally {
                synchronized(queue) { synthesizing = false }
                // A speak() that queued while we finished the last drain.
                if (synchronized(queue) { queue.isNotEmpty() }) drain()
            }
        }
    }

    private suspend fun synthesizeOnce(text: String) {
        val engine = TtsKit.getAvailableEngines().firstOrNull() ?: return
        val modelPath = CaptionSpeakerFactory.vitsBundlePath(context) ?: return
        val result = TtsKit.synthesize(
            text = text,
            engineType = engine,
            config = TtsConfig(modelPath = modelPath),
        ).getOrNull() ?: return

        val pcm = result.toPcm16()
        if (pcm.isEmpty()) return
        val sampleRate = result.sampleRate
        val player = track?.takeIf { it.sampleRate == sampleRate } ?: run {
            track?.release()
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                pcm.size * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).also { it.play(); track = it }
        }
        player.write(pcm, 0, pcm.size)
    }

    override fun stop() {
        synchronized(queue) { queue.clear() }
        track?.pause()
        track?.flush()
        track?.play()
    }

    override fun release() {
        synchronized(queue) { queue.clear() }
        scope.cancel()
        track?.release()
        track = null
    }
}
