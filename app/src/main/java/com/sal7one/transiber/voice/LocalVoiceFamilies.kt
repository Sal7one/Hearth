package com.sal7one.transiber.voice

import android.content.Context
import com.sal7one.hearth.tts.SpeechEngine
import com.sal7one.hearth.tts.magpie.*
import com.sal7one.hearth.tts.omnivoice.*
import com.sal7one.hearth.tts.moss.*
import com.sal7one.hearth.tts.oute.*
import com.sal7one.hearth.tts.chatterbox.*
import com.sal7one.hearth.tts.supertonic.SupertonicSpeechEngine
import com.sal7one.transiber.runtime.LocalModelPolicy
import java.io.File

/** One complete executable provider. Extra families add a descriptor rather than transport branches. */
internal data class LocalVoiceFamily(
    val id: String,
    val label: String,
    val voices: List<String>,
    val languages: Set<String>,
    val verifiedGender: Boolean = false,
    val runtimeAvailable: () -> Boolean,
    val packageReady: (File, String, VoiceGender) -> Boolean,
    val verifyPackage: (File, String, () -> Unit) -> Unit,
    val createEngine: (File) -> SpeechEngine,
)

/** Only executable bundled providers belong here; caption stages and transport remain independent. */
internal object LocalVoiceFamilies {
    val providers: List<LocalVoiceFamily> by lazy { listOf(
        LocalVoiceFamily("supertonic", "Supertonic 3", VoiceCatalog.voices, VoiceCatalog.languages, true,
            runtimeAvailable = { true },
            packageReady = { root, voice, gender -> VoiceModels(root).ready(voice, gender) },
            verifyPackage = { root, voice, cancel -> VoiceModels(root).verify(voice, cancel) },
            createEngine = ::SupertonicSpeechEngine),
        LocalVoiceFamily("magpie", "MagpieTTS v2607", MagpieCatalog.speakers, MagpieCatalog.languages,
            runtimeAvailable = MagpieSpeechEngine::available,
            packageReady = { root, _, _ -> MagpieModels(root).ready() },
            verifyPackage = { root, _, cancel -> MagpieModels(root).verify(cancel) },
            createEngine = { MagpieSpeechEngine(it) }),
        LocalVoiceFamily("omnivoice", "OmniVoice", OmniVoiceCatalog.designs, OmniVoiceCatalog.languages,
            runtimeAvailable = OmniVoiceSpeechEngine::available,
            packageReady = { root, _, _ -> OmniVoiceModels(root).ready() },
            verifyPackage = { root, _, cancel -> OmniVoiceModels(root).verify(cancel) },
            createEngine = ::OmniVoiceSpeechEngine),
        LocalVoiceFamily("moss-nano", "MOSS-TTS Nano", MossCatalog.voices, MossCatalog.languages,
            runtimeAvailable = MossSpeechEngine::available,
            packageReady = { root, _, _ -> MossModels(root).ready() },
            verifyPackage = { root, _, cancel -> MossModels(root).verify(cancel) },
            createEngine = { MossSpeechEngine(it) }),
        LocalVoiceFamily("chatterbox-v3", "Chatterbox Multilingual V3", ChatterboxCatalog.voices, ChatterboxCatalog.languages,
            runtimeAvailable = ChatterboxSpeechEngine::available,
            packageReady = { root, _, _ -> ChatterboxModels(root).ready() },
            verifyPackage = { root, _, cancel -> ChatterboxModels(root).verify(cancel) },
            createEngine = { ChatterboxSpeechEngine(it) }),
        LocalVoiceFamily("oute", "OuteTTS 1.0", OuteCatalog.voices, OuteCatalog.languages,
            runtimeAvailable = OuteSpeechEngine::available,
            packageReady = { root, _, _ -> OuteModels(root).ready() },
            verifyPackage = { root, _, cancel -> OuteModels(root).verify(cancel) },
            createEngine = ::OuteSpeechEngine),
    ) }
    val ids get() = providers.map { it.id }.toSet()
    fun find(family: String) = providers.firstOrNull { it.id == family }
    private fun requireFamily(family: String) = checkNotNull(find(family)) { "Unknown speech model family: $family" }
    fun label(family: String) = requireFamily(family).label
    fun voices(family: String) = find(family)?.voices.orEmpty()
    fun languages(family: String) = find(family)?.languages.orEmpty()
    fun verifiedGender(family: String) = find(family)?.verifiedGender == true
    fun available(context: Context, family: String) = LocalModelPolicy.allowed(context) && find(family)?.runtimeAvailable?.invoke() == true
    fun requireAllowed(context: Context, family: String, feature: String = "downloaded speech voices") {
        LocalModelPolicy.requireAllowed(context, feature)
        check(available(context, family)) { "${label(family)} requires its packaged native runtime in this app process ($feature)" }
    }
    fun directory(context: Context, family: String) = File(context.filesDir, if(family == "supertonic") "voice-models" else "voice-models/$family")
    fun ready(context: Context, choice: VoiceChoice, gender: VoiceGender = VoiceGender.ANY): Boolean {
        val family = find(choice.family) ?: return false
        return LocalVoiceReadiness.ready(choice.voice, gender, LocalModelPolicy.allowed(context), family.voices, family.verifiedGender,
            runtimeAvailable = family.runtimeAvailable,
            packageReady = { family.packageReady(directory(context, family.id), choice.voice, gender) })
    }
    fun verify(context: Context, choice: VoiceChoice, cancelled: () -> Unit = {}) {
        requireAllowed(context, choice.family)
        val family = requireFamily(choice.family)
        require(choice.voice in family.voices) { "Select a supported ${family.label} voice" }
        family.verifyPackage(directory(context, family.id), choice.voice, cancelled)
    }
    fun engine(context: Context, family: String) = requireFamily(family).createEngine(directory(context, family))
}
