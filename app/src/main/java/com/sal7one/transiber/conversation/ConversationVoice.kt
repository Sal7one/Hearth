package com.sal7one.transiber.conversation

import com.sal7one.transiber.voice.OfflineVoicePolicy
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Installed offline voices only: no silent default-language fallback or network speech. */
internal class ConversationVoice(context: Context, private val changed: (Boolean, String?) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var active: String? = null
    private val tts = TextToSpeech(context.applicationContext) { code ->
        ready = code == TextToSpeech.SUCCESS
        if (!ready) changed(false, "System text-to-speech initialization failed: $code")
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { done(id, null) }
            @Deprecated("Android callback") override fun onError(id: String?) { done(id, "System text-to-speech failed") }
            override fun onError(id: String?, errorCode: Int) { done(id, "System text-to-speech error: $errorCode") }
            override fun onStop(id: String?, interrupted: Boolean) { done(id, null) }
        })
    }
    private fun done(id: String?, error: String?) { main.post { if (active == id) { active = null; changed(false, error) } } }
    fun speak(turn: ConversationTurn) {
        stop()
        if (!ready) { changed(false, "System speech is not ready. Try Play again."); return }
        val locale = Locale.forLanguageTag(turn.target)
        val voices = tts.voices.orEmpty()
        val selected = OfflineVoicePolicy.select(voices.map {
            OfflineVoicePolicy.Candidate(it.name, it.locale, it.isNetworkConnectionRequired, it.quality)
        }, locale)
        val voice = voices.firstOrNull { it.name == selected }
        if (voice == null) { changed(false, "No installed offline ${locale.getDisplayLanguage(Locale.ENGLISH)} voice. Install one in Android text-to-speech settings."); return }
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) { changed(false, "System text-to-speech could not select ${voice.name}"); return }
        val id = UUID.randomUUID().toString(); active = id
        changed(true, null)
        val result = tts.speak(turn.translation, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) done(id, "System text-to-speech speak returned $result")
    }
    fun stop() { active = null; tts.stop(); changed(false, null) }
    override fun close() { stop(); tts.shutdown() }
}
