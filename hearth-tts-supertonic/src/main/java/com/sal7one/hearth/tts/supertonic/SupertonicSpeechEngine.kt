package com.sal7one.hearth.tts.supertonic

import com.sal7one.common_jni.voice.SupertonicVoice
import com.sal7one.common_jni.voice.VoiceStyle
import com.sal7one.common_jni.voice.VoiceText
import com.sal7one.hearth.tts.PcmAudio
import com.sal7one.hearth.tts.SpeechEngine
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.SpeechVoice
import com.sal7one.hearth.tts.VoiceGender
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** No downloader or app dependency. Files must already match the public pinned inventory. */
class SupertonicSpeechEngine(private val directory: File) : SpeechEngine {
    override val id = "supertonic-3"
    override val voices = SupertonicCatalog.voices.map { name ->
        SpeechVoice(name, if (name.startsWith('F')) "Female ${name.drop(1)}" else "Male ${name.drop(1)}",
            SupertonicCatalog.languages, if (name.startsWith('F')) VoiceGender.FEMALE else VoiceGender.MALE)
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var native: SupertonicVoice? = null
    private var indexer: LongArray? = null
    private val styles = mutableMapOf<String, VoiceStyle>()
    private var closed = false

    @Synchronized override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "Supertonic voice engine is closed" }
        require(voice in voices && voice.supports(language)) { "Unsupported Supertonic voice or language" }
        cancellation.throwIfCancelled()
        check(available()) { availabilityReason() }
        if (cancelled.getAndSet(false)) { native?.close(); native = null }
        val style = styles.getOrPut(voice.id) {
            verify(SupertonicCatalog.core + SupertonicCatalog.file("${voice.id}.json"))
            style(voice.id)
        }
        if (indexer == null) indexer = JSONArray(File(directory, "unicode_indexer.json").readText()).let { data -> LongArray(data.length(), data::getLong) }
        checkCancelled(); cancellation.throwIfCancelled()
        val runtime = native ?: SupertonicVoice(directory).also { native = it }
        checkCancelled(); cancellation.throwIfCancelled()
        val samples = runtime.synthesize(VoiceText.ids(text, Locale.forLanguageTag(language).language, checkNotNull(indexer)), style, options.qualitySteps, options.rate)
        check(samples.isNotEmpty()) { "Supertonic produced no speech" }
        var offset = 0
        while (offset < samples.size) {
            checkCancelled()
            val end = minOf(offset + SupertonicVoice.SAMPLE_RATE, samples.size)
            emit(PcmAudio(samples.copyOfRange(offset, end), SupertonicVoice.SAMPLE_RATE))
            offset = end
        }
    }

    private fun verify(assets: List<SupertonicAsset>) {
        assets.forEach { asset ->
            checkCancelled()
            val file = File(directory, asset.filename)
            check(file.isFile && file.length() == asset.bytes) { "Install the pinned Supertonic file ${asset.filename} first" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256) { "Supertonic checksum failed: ${asset.filename}" }
        }
    }
    private fun style(voice: String): VoiceStyle {
        val json = JSONObject(File(directory, "$voice.json").readText())
        fun values(key: String, rows: Int, columns: Int): FloatArray {
            val value = json.getJSONObject(key); val dims = value.getJSONArray("dims")
            require(dims.length() == 3 && dims.getInt(0) == 1 && dims.getInt(1) == rows && dims.getInt(2) == columns) { "Invalid Supertonic style dimensions" }
            val data = value.getJSONArray("data").getJSONArray(0)
            return FloatArray(rows * columns) { index -> data.getJSONArray(index / columns).getDouble(index % columns).toFloat().also { require(it.isFinite()) } }
        }
        return VoiceStyle(values("style_ttl", 50, 256), values("style_dp", 8, 16))
    }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("Supertonic speech stopped") }
    override fun cancel() { cancelled.set(true); native?.cancel() }
    @Synchronized override fun close() { if (!closed) { closed = true; native?.close(); native = null; styles.clear(); indexer = null } }

    companion object {
        fun available(): Boolean = SupertonicVoice.available()
        fun availabilityReason(): String = SupertonicVoice.availabilityReason()
    }
}
