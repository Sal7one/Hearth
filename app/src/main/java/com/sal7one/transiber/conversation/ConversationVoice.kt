package com.sal7one.transiber.conversation

import android.content.Context
import com.sal7one.transiber.voice.VoicePlayer

/** Traveler modes use the same selected Android/local/server voice as Hearth's text tools. */
internal class ConversationVoice(context: Context, changed: (Boolean,String?)->Unit) : AutoCloseable {
    private val player=VoicePlayer(context,changed=changed)
    fun speak(turn: ConversationTurn,system: Boolean?=null)=player.speak(turn.translation,turn.target,
        when(system){true->com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM;false->com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM;null->com.sal7one.transiber.voice.VoicePlaybackMode.DEFAULT})
    fun stop()=player.stop()
    override fun close()=player.close()
}
