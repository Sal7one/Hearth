package com.sal7one.transiber.caption

import android.os.Build
import android.os.Process
import com.sal7one.common_jni.speech.SpeechBackend
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.transiber.models.SpeechDownloads

/** New native ports currently ship only for a real ARM64 process. Legacy engines keep all ABIs. */
internal object SpeechAbiPolicy {
    private val arm64Only = setOf(SpeechBackend.COHERE, SpeechBackend.FUNASR_MLT, SpeechBackend.VOXTRAL_REALTIME, SpeechBackend.AUDAR, SpeechBackend.OWSM)

    fun supports(profile: SpeechProfile, primaryAbi: String?, is64BitProcess: Boolean): Boolean =
        profile.backend !in arm64Only || (primaryAbi == "arm64-v8a" && is64BitProcess)

    fun requireSupported(profile: SpeechProfile) {
        if (profile.backend !in arm64Only) return
        check(supports(profile, Build.SUPPORTED_ABIS.firstOrNull(), Process.is64Bit())) {
            "${profile.id} requires an ARM64 app process; this native speech runtime is unavailable on this ABI"
        }
    }

    /** Enforce again for saved download rows and callers outside the model explorer. */
    fun requireCatalogModel(id: String) {
        SpeechDownloads.find(id)?.profile?.let(::requireSupported)
        com.sal7one.transiber.models.SpeechComponents.forPart(id)?.profile?.let(::requireSupported)
        com.sal7one.transiber.models.SpeechComponents.find(id)?.profile?.let(::requireSupported)
    }
}
