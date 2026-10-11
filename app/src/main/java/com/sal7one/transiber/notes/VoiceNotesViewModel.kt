package com.sal7one.transiber.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** One recording owner across Activity recreation. Recovery must never race its live WAV writer. */
internal class VoiceNotesViewModel(application: Application) : AndroidViewModel(application) {
    val controller = VoiceNotesController(application)
    override fun onCleared() { controller.close(); super.onCleared() }
}
