package com.sal7one.transiber.setup

import android.app.Application
import com.sal7one.common_jni.speech.SpeechModelPackage
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.models.PublisherSpeechReceipts
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.voice.VoiceChoice
import com.sal7one.transiber.voice.VoiceSettings
import com.sal7one.transiber.i18n.uiText
import com.sal7one.transiber.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PortableSetupActionsTest {
    @Test fun magpieGenderFiltersFailPreviewAndApplyWithoutChangingSavedChoices() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val previous = VoiceSettings.choice(context)
        try {
            val selected = VoiceChoice(backend = "supertonic", voice = "John", family = "magpie")
            VoiceSettings.save(context, selected)
            val actions = PortableSetupActions(context)
            val error = context.uiText()(R.string.magpie_gender_unverified)
            listOf(CaptionSpeakerChoice.NATIVE, CaptionSpeakerChoice.SHARED, CaptionSpeakerChoice.CUSTOM).forEach { choice ->
                val voice = PortableVoiceStage(enabled = true, choice = choice, gender = SpeakerGender.FEMALE,
                    voiceId = "John".takeIf { choice == CaptionSpeakerChoice.NATIVE },
                    family = "magpie".takeIf { choice == CaptionSpeakerChoice.NATIVE })
                val setup = PortableSetup("Unsupported gender filter", voice = voice)
                val preview = actions.preview(setup)
                assertFalse(preview.ready)
                assertTrue(preview.issues.toString(), error in preview.issues)
                val failure = runCatching { actions.apply(setup) }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains(error))
                assertEquals(selected, VoiceSettings.choice(context))
                assertFalse(CaptionSpeakerFactory.isAvailable(context, choice, SpeakerGender.FEMALE))
            }
        } finally {
            VoiceSettings.save(context, previous)
        }
    }

    @Test fun trustedPublisherReceiptResolvesOfflineAndExportsDownloadableCatalogReference() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "speech-models")
        val source = checkNotNull(SpeechDownloads.find("moonshine-tiny-en-v2"))
        val roles = mapOf("model" to "tokens.txt", "encoder" to "encoder.ort", "decoder" to "decoder.ort")
        val files = roles.values.associateWith { "fixture-$it".toByteArray() }
        val manifest = JSONObject().put("schemaVersion", 1).put("profile", source.profile.id).put("roles", JSONObject(roles))
            .put("files", JSONArray().apply { files.forEach { (name, bytes) -> put(JSONObject().put("path", name).put("bytes", bytes.size)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })) } })
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (files + (SpeechModelPackage.MANIFEST to manifest.toString().toByteArray())).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val imported = LocalSpeechModels(root).importZip(output.toByteArray().inputStream())
        // Fixture the installer-owned trust receipt, not publisher weights. The real archive
        // checksum/receipt creation path is covered by PublisherSpeechReceiptTest.
        PublisherSpeechReceipts(root).record(imported.id, source, SpeechModelPackage.verify(imported.root).manifestSha256)
        File(root, "speech-${UUID.randomUUID()}").apply { mkdirs() }
            .resolve(SpeechModelPackage.MANIFEST).writeText("{broken")
        CaptionConfigStore.update(context) { it.copy(engine = CaptionEngineChoice.MOONSHINE, modelId = imported.id,
            streamLanguage = "en", mode = CaptionMode.CAPTIONS, localTranslationEnabled = false) }
        val actions = PortableSetupActions(context)
        val setup = PortableSetup("Publisher captions", PortableSpeechStage(PortableModelRef("moonshine", source.profile.id), "en"))
        val before = context.getSharedPreferences("file_downloads", 0).all
        assertTrue(actions.preview(setup).ready)
        assertTrue(actions.preview(PortableSetup("Custom captions", PortableSpeechStage(
            PortableModelRef("moonshine", profile = source.profile.id, sha256 = ModelIntegrity.inspect(imported.root).digest.hex,
                digestKind = "tree"), "en"))).ready)
        assertEquals(before, context.getSharedPreferences("file_downloads", 0).all)
        val exported = actions.exportCurrent(includeTranslation = false, includeVoice = false)
        assertEquals(source.profile.id, exported.speech!!.model.catalogId)
        assertNull(exported.speech.model.sha256)
        val roundTrip = PortableSetup.parse(exported.toJson())
        assertTrue(actions.preview(roundTrip).ready)
        actions.apply(roundTrip)
        val saved = CaptionConfigStore.config(context).first()
        assertEquals(imported.id, saved.modelId)
        assertEquals(CaptionMode.CAPTIONS, saved.mode)
        assertFalse(saved.localTranslationEnabled)
    }
}
