package com.sal7one.transiber.translation

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.Ct2DownloadArchive
import com.sal7one.common_jni.translation.Ct2File
import com.sal7one.common_jni.translation.Ct2ModelSpec
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.caption.CaptionMode
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.TranslationTarget
import com.sal7one.transiber.downloads.DownloadAssets
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.preferences.UserPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers

/** Tiny pinned transport/package fixtures exercise installation; these are not inference models. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class Ct2ArchiveDownloadTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("file_downloads",Context.MODE_PRIVATE)
    private val modelId = "ct2-archive-download-fixture"
    private val payload = linkedMapOf("model.bin" to "pinned runtime model".toByteArray(),
        "config.json" to "{\"decoder_start_token\":0}".toByteArray())
    private val sourceUri = Uri.parse("content://ct2-archive-fixture/runtime.zip")

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("arm64-v8a"))
        ShadowVMRuntime.setIs64Bit(true)
        preferences.edit().clear().commit()
        File(context.filesDir,"ct2-models").deleteRecursively()
    }

    @After fun cleanup() {
        preferences.edit().clear().commit()
        File(context.filesDir,"ct2-models").deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun zip(files: Map<String,ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output -> files.forEach { (name,content) ->
            output.putNextEntry(ZipEntry(name));output.write(content);output.closeEntry()
        } }
    }.toByteArray()

    private fun archive(identity: String=modelId, files: Map<String,ByteArray> = payload): ByteArray =
        zip(files + (Ct2Package.MANIFEST to JSONObject().put("schemaVersion",1).put("modelId",identity).toString().toByteArray()))

    private fun spec(zip: ByteArray): Ct2ModelSpec = Ct2Catalog.models.first().copy(id=modelId,
        files=payload.map { (name,bytes) -> Ct2File(name,bytes.size.toLong(),sha(bytes),"fixture/runtime","0".repeat(40)) },
        archive=Ct2DownloadArchive("https://example.com/pinned-runtime.zip",zip.size.toLong(),sha(zip)))

    private fun journal(spec: Ct2ModelSpec, input: InputStream, complete: Boolean=true) {
        shadowOf(context.contentResolver).registerInputStream(sourceUri,input)
        val part=Ct2Package.parts(spec).single()
        val record=JSONObject().put("id",-901L).put("title",part.downloadName).put("model",part.id)
            .put("phase","Installing").put("downloadComplete",complete).put("uri",sourceUri.toString())
            .put("bytes",part.file.bytes).put("total",part.file.bytes)
        preferences.edit().putString("download_-901",record.toString()).putStringSet("public_ids",setOf("-901")).commit()
    }

    private fun assertNoStaging() {
        assertTrue(File(context.filesDir,"ct2-models").listFiles().orEmpty().none { it.name.startsWith(".") })
    }

    @Test fun archiveTransportHasOnePinnedPartWhileExistingModelComponentsKeepTheirIdentities() {
        val packaged=Ct2Catalog.models.filter { it.archive!=null }
        assertEquals("Every Jisr precision/direction must use its verified runtime ZIP",4,packaged.size)
        packaged.forEach { model ->
            val archive=checkNotNull(model.archive)
            val part=Ct2Package.parts(model).single()
            assertEquals("${model.id}-archive",part.id)
            assertEquals("runtime.zip",part.file.name)
            assertEquals(archive.url,part.file.url)
            assertEquals(archive.url,DownloadSpec.parse(part.file.url,part.downloadName).url)
            assertEquals(archive.bytes,part.file.bytes)
            assertEquals(archive.sha256,part.file.sha256)
            assertEquals(part,Ct2Package.part(part.id))
            val asset=checkNotNull(DownloadAssets.find(part.id))
            assertEquals(archive.bytes,asset.bytes)
            assertEquals(Math.addExact(model.bytes,archive.bytes),asset.installedBytes)
        }
        for(id in listOf("ct2-quickmt-en-ar","ct2-quickmt-ar-en","ct2-small100-int8")) {
            val model=checkNotNull(Ct2Catalog.find(id))
            assertNull(model.archive)
            val parts=Ct2Package.parts(model)
            assertEquals(model.files.size,parts.size)
            parts.forEachIndexed { index,part ->
                assertEquals("${model.id}-file-$index",part.id)
                assertEquals(model.files[index],part.file)
            }
        }
    }

    @Test fun aCompletedPinnedArchiveRepairsThePrivateCopyWithoutChangingTheDownloadedOriginal() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val bytes=archive();val model=spec(bytes)
        val target=Ct2Package.directory(context,model).apply { mkdirs() }
        File(target,"old-partial").writeText("damaged previous install")
        journal(model,ByteArrayInputStream(bytes))
        assertEquals(model.id,Ct2Package.install(context,FileDownloads(context),model))
        assertTrue(Ct2Package.installed(context,model))
        model.verify(target)
        assertEquals(model.id,JSONObject(File(target,Ct2Package.MANIFEST).readText()).getString("modelId"))
        assertFalse(File(target,"old-partial").exists())
        assertEquals(sourceUri,FileDownloads(context).uri(-901L))
        assertNoStaging()
    }

    @Test fun archiveCompletionMustBeRecordedBeforeOpeningOrPublishingThePackage() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val bytes=archive();val model=spec(bytes)
        var read=false
        journal(model,object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int { read=true;return super.read(buffer,offset,length) }
        },complete=false)
        assertNull(Ct2Package.install(context,FileDownloads(context),model))
        assertFalse(read)
        assertFalse(Ct2Package.directory(context,model).exists())
    }

    @Test fun usingAnInstalledJisrArchiveSelectsItsDirectionWithoutChangingSpeechOrOverlayPreferences() = runBlocking {
        UserPreferences.initialize(context)
        val model=checkNotNull(Ct2Catalog.find("ct2-jisr-50m-en-ar-int8"))
        val part=Ct2Package.parts(model).single()
        val before=CaptionOverlayConfig(engine=CaptionEngineChoice.PHONON,modelId="selected-speech",
            streamLanguage="en",mode=CaptionMode.CAPTIONS,target=TranslationTarget.of("fr"),
            textTranslationProviderId="deepl",localTranslationModelId="another-translator",minimalHeader=true)
        CaptionConfigStore.update(context) { before }
        val target=Ct2Package.directory(context,model).apply { mkdirs() }
        // Presence-only Use action: real inference still verifies the publisher component hashes.
        model.files.forEach { component -> RandomAccessFile(File(target,component.name),"rw").use { it.setLength(component.bytes) } }
        val record=JSONObject().put("id",-901L).put("model",part.id).put("phase","Installed").put("installed",model.id)
        preferences.edit().putString("download_-901",record.toString()).commit()
        val row=FileDownload(-901,part.downloadName,DownloadManager.STATUS_SUCCESSFUL,0,part.file.bytes,part.file.bytes,
            phase="Installed",installedId=model.id,modelId=part.id)
        FileDownloads(context).selectInstalled(row)
        assertEquals(before.copy(mode=CaptionMode.TRANSLATE,textTranslationProviderId="local",localTranslationEnabled=true,
            localTranslationModelId=model.id,target=TranslationTarget.ARABIC),CaptionConfigStore.config(context).first())
    }

    @Test fun changedTransportOrComponentsAndWrongModelIdentityCannotReplaceAnExistingInstall() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val valid=archive();val model=spec(valid)
        val wrongIdentity=archive(identity="ct2-another-direction")
        val changedComponent=archive(files=payload + ("model.bin" to ByteArray(payload.getValue("model.bin").size)))
        val nestedManifest=zip(payload + ("nested/${Ct2Package.MANIFEST}" to
            JSONObject().put("schemaVersion",1).put("modelId",modelId).toString().toByteArray()))
        val extra=archive(files=payload + ("unrequested" to byteArrayOf(1)))
        val cases=listOf(
            model to valid.copyOf(valid.size-1), // The original download's digest changed.
            model.copy(archive=model.archive!!.copy(bytes=valid.size.toLong()+1)) to valid, // Exact transport size matters too.
            spec(wrongIdentity) to wrongIdentity, // A valid ZIP pin cannot grant another model identity.
            spec(changedComponent) to changedComponent, // Transport pin cannot bypass runtime component pins.
            spec(nestedManifest) to nestedManifest,
            spec(extra) to extra,
        )
        val target=Ct2Package.directory(context,model).apply { mkdirs() }
        val previous=File(target,"previous-install").apply { writeText("keep this copy") }
        cases.forEach { (requested,bytes) ->
            journal(requested,ByteArrayInputStream(bytes))
            assertThrows(Exception::class.java) { runBlocking { Ct2Package.install(context,FileDownloads(context),requested) } }
            assertEquals("keep this copy",previous.readText())
            assertFalse(File(target,"model.bin").exists())
            assertNoStaging()
        }
    }

    @Test fun cancelledArchiveCopyKeepsTypedCancellationAndCannotReplaceThePrivateCopy() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val bytes=archive();val model=spec(bytes)
        val target=Ct2Package.directory(context,model).apply { mkdirs() }
        val previous=File(target,"previous-install").apply { writeText("keep this copy") }
        journal(model,object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int { throw CancellationException("archive fixture cancelled") }
        })
        val failure=assertThrows(CancellationException::class.java) {
            runBlocking { Ct2Package.install(context,FileDownloads(context),model) }
        }
        assertEquals("archive fixture cancelled",failure.message)
        assertEquals("keep this copy",previous.readText())
        assertNoStaging()
    }

    @Test fun unsupportedArchitectureRejectsArchiveEnqueueAndInstallBeforeOpeningTheOriginal() {
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("x86_64"))
        val downloadable=checkNotNull(Ct2Catalog.find("ct2-jisr-50m-en-ar-int8"))
        val downloads=FileDownloads(context)
        val enqueue=assertThrows(IllegalStateException::class.java) { Ct2Package.enqueue(context,downloads,downloadable) }
        assertTrue(enqueue.message.orEmpty().contains("ARM64 runtime"))
        assertTrue(preferences.all.isEmpty())
        val bytes=archive();val model=spec(bytes)
        var read=false
        journal(model,object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int { read=true;return super.read(buffer,offset,length) }
        })
        val install=assertThrows(IllegalStateException::class.java) {
            runBlocking { Ct2Package.install(context,downloads,model) }
        }
        assertTrue(install.message.orEmpty().contains("ARM64 runtime"))
        assertFalse(read)
        assertFalse(Ct2Package.directory(context,model).exists())
    }
}
