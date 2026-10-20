package com.sal7one.transiber.translation

import android.app.Application
import android.content.Context
import android.os.Build
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.Ct2File
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.FileDownloads
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class Ct2CompletionTest {
    @Test fun restoredWaitingRowCannotBypassTheArchitectureGateInThePlayWorker() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val context=RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("x86_64"));ShadowVMRuntime.setIs64Bit(true)
        val part=Ct2Package.parts(Ct2Catalog.models.first()).first()
        val record=JSONObject().put("id",-7L).put("model",part.id).put("modelsFolder",true)
            .put("phase","Waiting").put("downloadComplete",true).put("uri","content://must-not-open/component")
        context.getSharedPreferences("file_downloads",Context.MODE_PRIVATE).edit().clear()
            .putString("download_-7",record.toString()).putStringSet("public_ids",setOf("-7")).commit()
        // The FOSS variant deliberately has no DownloadService; reflect only after its feature gate.
        val type=Class.forName("com.sal7one.transiber.downloads.DownloadService").asSubclass(android.app.Service::class.java)
        val controller=Robolectric.buildService(type).create()
        try {
            val method=type.getDeclaredMethod("runDownload",java.lang.Long.TYPE,Continuation::class.java).apply { isAccessible=true }
            val continuation=object : Continuation<Unit> {
                override val context=EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { error("The architecture guard must reject before suspension") }
            }
            val thrown=assertThrows(InvocationTargetException::class.java) { method.invoke(controller.get(),-7L,continuation) }
            assertTrue(thrown.targetException is IllegalStateException)
            assertTrue(thrown.targetException.message.orEmpty(),thrown.targetException.message.orEmpty().contains("ARM64 runtime"))
        } finally { controller.destroy() }
    }

    @Test fun launchBindingDoesNotMistakeAPartialCt2FolderForAnAvailableModel() = runBlocking {
        val context=RuntimeEnvironment.getApplication();val spec=Ct2Catalog.models.first()
        val root=Ct2Package.directory(context,spec).apply { mkdirs() }
        File(root,"config.json").writeText("partial old install")
        com.sal7one.transiber.preferences.UserPreferences.store(context,"conversation-translators").edit().putString("local-model",spec.id).commit()
        try {
            val binding=com.sal7one.transiber.preferences.ModelSourceBindings.verifyAtLaunch(context).single { it.modelId==spec.id }
            assertFalse(binding.available)
            assertTrue(binding.error.orEmpty(),binding.error.orEmpty().contains("missing or truncated"))
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedAbiRejectsDownloadsAndImportBeforeJournalMutationOrReadingInput() {
        val context=RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("x86_64"));ShadowVMRuntime.setIs64Bit(true)
        val prefs=context.getSharedPreferences("file_downloads",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val spec=Ct2Catalog.models.first();val part=Ct2Package.parts(spec).first()
        val downloads=FileDownloads(context)
        fun abiDenied(action: () -> Unit) {
            val error=assertThrows(IllegalStateException::class.java,action)
            assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("ARM64 runtime"))
        }
        val enqueueError=assertThrows(IllegalStateException::class.java) {
            downloads.enqueue(com.sal7one.transiber.downloads.DownloadSpec.parse(part.file.url,part.downloadName),true,part.id)
        }
        assertTrue(enqueueError.message.orEmpty(),enqueueError.message.orEmpty().contains(if(ByokPolicy.FEATURE_BYOK) "ARM64 runtime" else "offline build"))
        assertTrue(prefs.all.isEmpty())
        val record=JSONObject().put("id",-7L).put("model",part.id).put("modelsFolder",true).put("phase","Paused")
        prefs.edit().putString("download_-7",record.toString()).putStringSet("public_ids",setOf("-7")).commit()
        val retryError=assertThrows(IllegalStateException::class.java) { downloads.retry(-7L) }
        assertTrue(retryError.message.orEmpty(),retryError.message.orEmpty().contains("ARM64 runtime") || !ByokPolicy.FEATURE_BYOK && retryError.message=="Check failed.")
        assertEquals("Paused",JSONObject(prefs.getString("download_-7","")!!).getString("phase"))
        abiDenied { runBlocking { downloads.installModel(-8L,part.id) } }
        var read=false
        val input=object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(): Int { read=true;return super.read() }
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int { read=true;return super.read(buffer,offset,length) }
        }
        abiDenied { Ct2Package.importZip(context,input) }
        assertFalse("Architecture checks must happen before a selected ZIP is consumed",read)
    }

    @Test fun arm64HardwareWithA32BitProcessCannotOpenCt2() {
        val context=RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("arm64-v8a","armeabi-v7a"));ShadowVMRuntime.setIs64Bit(false)
        val error=assertThrows(IllegalStateException::class.java) { Ct2Package.requireRuntime(context) }
        assertTrue(error.message.orEmpty(),error.message.orEmpty().contains("64-bit ARM app process"))
    }

    @Test fun finalComponentStillInInstallingPhaseCanPublishOnlyAfterEveryVerifiedFileExists() = runBlocking {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val context=RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("arm64-v8a"));ShadowVMRuntime.setIs64Bit(true)
        val data="verified file".toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val spec=Ct2Catalog.models.first().copy(id="ct2-completion-fixture",files=listOf(
            Ct2File("model.bin",data.size.toLong(),hash,"publisher/test","0".repeat(40)),
            Ct2File("config.json",data.size.toLong(),hash,"publisher/test","0".repeat(40))))
        val prefs=context.getSharedPreferences("file_downloads",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val source=File(context.cacheDir,"ct2-original").apply { writeBytes(data) }
        val parts=Ct2Package.parts(spec)
        fun record(index: Int,verified: Boolean) {
            val id=-100L-index
            val json=JSONObject().put("id",id).put("title",parts[index].downloadName).put("model",parts[index].id)
                .put("phase",if(index==0) "Complete" else "Installing").put("downloadComplete",verified)
                .put("uri",source.toURI().toString()).put("bytes",data.size).put("total",data.size)
            prefs.edit().putString("download_$id",json.toString()).putStringSet("public_ids",setOf("-100","-101")).commit()
        }
        try {
            record(0,true);record(1,false)
            val downloads=FileDownloads(context)
            assertNull(Ct2Package.install(context,downloads,spec));assertFalse(Ct2Package.directory(context,spec).exists())
            record(1,true)
            assertEquals(spec.id,Ct2Package.install(context,downloads,spec))
            spec.verify(Ct2Package.directory(context,spec))
        } finally { Ct2Package.directory(context,spec).deleteRecursively();source.delete() }
    }
}
