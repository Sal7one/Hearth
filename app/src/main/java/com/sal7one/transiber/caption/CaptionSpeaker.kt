package com.sal7one.transiber.caption

import android.content.Context
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.voice.*
import java.io.File

interface CaptionSpeaker {
    fun speak(text: String, languageTag: String): Boolean
    /** Preferences from the caption settings; engines apply what they support. */
    fun speak(text: String, languageTag: String, gender: SpeakerGender, volumePercent: Int): Boolean =
        speak(text, languageTag)
    fun stop()
    fun release()
}
object CaptionSpeakerFactory {
    fun isAvailable(context: Context, choice: CaptionSpeakerChoice): Boolean = when(choice) {
        CaptionSpeakerChoice.SYSTEM, CaptionSpeakerChoice.SHARED -> true
        CaptionSpeakerChoice.CUSTOM -> VoiceSettings.customBackend(context)!=null
        CaptionSpeakerChoice.NATIVE -> VoiceModels(File(context.filesDir,"voice-models")).ready(VoiceSettings.choice(context).voice)
        CaptionSpeakerChoice.CLOUD -> ByokPolicy.FEATURE_BYOK && ApiKeyStore.hasOpenAiKey(context)
    }
    fun create(context: Context, choice: CaptionSpeakerChoice, onError: (String)->Unit = {}): CaptionSpeaker = when(choice) {
        CaptionSpeakerChoice.CLOUD -> CloudTtsSpeaker(ApiKeyStore.getOpenAiKey(context),CloudConfigStore.baseUrl(context),CloudConfigStore.ttsModel(context),CloudConfigStore.ttsVoice(context))
        else -> SharedCaptionVoice(context,when(choice){CaptionSpeakerChoice.SYSTEM->"system";CaptionSpeakerChoice.NATIVE->"supertonic";else->null},onError,choice==CaptionSpeakerChoice.CUSTOM)
    }
}
private class SharedCaptionVoice(context: Context,backend: String?,error: (String)->Unit,private val custom: Boolean=false):CaptionSpeaker {
    private val player=VoicePlayer(context,backend){_,message->if(message!=null)error(message)}
    override fun speak(text: String,languageTag: String):Boolean {player.enqueue(text,languageTag,if(custom)VoicePlaybackMode.CUSTOM else VoicePlaybackMode.DEFAULT);return text.isNotBlank()}
    override fun speak(text: String,languageTag: String,gender: SpeakerGender,volumePercent: Int):Boolean {
        player.enqueue(text,languageTag,if(custom)VoicePlaybackMode.CUSTOM else VoicePlaybackMode.DEFAULT,
            gender.toVoiceGender(),volumePercent.coerceIn(0,100)/100f)
        return text.isNotBlank()
    }
    override fun stop()=player.stop()
    override fun release()=player.close()
}

/** Maps the caption-level preference onto the shared voice-engine type. */
internal fun SpeakerGender.toVoiceGender(): VoiceGender = when (this) {
    SpeakerGender.ANY -> VoiceGender.ANY
    SpeakerGender.FEMALE -> VoiceGender.FEMALE
    SpeakerGender.MALE -> VoiceGender.MALE
}
