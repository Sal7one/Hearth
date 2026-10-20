package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.common_jni.translation.Ct2File
import com.sal7one.common_jni.translation.Ct2ModelSpec
import com.sal7one.transiber.downloads.DownloadAsset
import com.sal7one.transiber.downloads.DownloadSpec
import com.sal7one.transiber.downloads.FileDownloads
import com.sal7one.transiber.runtime.LocalModelPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** CT2 packages are independent directional installs; downloaded parts never activate a model. */
internal object Ct2Package {
    const val MANIFEST = "hearth-translation.json"
    fun runtimeSupported(): Boolean = android.os.Process.is64Bit() && "arm64-v8a" in android.os.Build.SUPPORTED_ABIS
    fun requireRuntime(context: Context) {
        LocalModelPolicy.requireAllowed(context,"CT2 translation")
        check(runtimeSupported()) { "CT2 translation requires the ARM64 runtime and a 64-bit ARM app process. Select an available translator on this device." }
    }
    data class Part(val spec: Ct2ModelSpec, val file: Ct2File, val index: Int) {
        val id get() = "${spec.id}-file-$index"
        val downloadName get() = "${spec.id}-${file.name}"
        fun asset() = DownloadAsset(id,file.url,file.bytes,file.sha256,spec.bytes)
    }
    fun parts(spec: Ct2ModelSpec) = if (!spec.downloadEnabled) emptyList() else spec.files.mapIndexed { index,file -> Part(spec,file,index) }
    fun part(id: String) = Ct2Catalog.models.asSequence().flatMap { parts(it).asSequence() }.firstOrNull { it.id==id }
    fun directory(context: Context,spec: Ct2ModelSpec) = File(context.filesDir,"ct2-models/${spec.id}")
    fun installed(context: Context,spec: Ct2ModelSpec): Boolean = directory(context,spec).let { root ->
        root.isDirectory && spec.files.all { File(root,it.name).let { file -> file.isFile && file.length()==it.bytes } }
    }
    fun enqueue(context: Context,downloads: FileDownloads,spec: Ct2ModelSpec) {
        requireRuntime(context)
        check(spec.downloadEnabled) { "Import the verified ${spec.label} runtime ZIP from the Hearth release. Original training files are not runtime packages." }
        parts(spec).forEach { downloads.enqueue(DownloadSpec.parse(it.file.url,it.downloadName),true,it.id) }
    }
    private fun manifest(spec: Ct2ModelSpec) = JSONObject().put("schemaVersion",1).put("modelId",spec.id).toString()
    private fun publish(context: Context,spec: Ct2ModelSpec,staged: File): File {
        requireRuntime(context)
        spec.verify(staged)
        val target=directory(context,spec); val backup=File(target.parentFile,".damaged-${UUID.randomUUID()}")
        if(target.exists()) Files.move(target.toPath(),backup.toPath())
        try { Files.move(staged.toPath(),target.toPath()) }
        catch(e: Exception) { if(backup.exists())Files.move(backup.toPath(),target.toPath());throw e }
        backup.deleteRecursively();return target
    }
    suspend fun install(context: Context,downloads: FileDownloads,spec: Ct2ModelSpec): String? {
        requireRuntime(context)
        if (!spec.downloadEnabled) return null
        val job=currentCoroutineContext()
        val rows=parts(spec).map { part -> downloads.list().firstOrNull { it.modelId==part.id && downloads.record(it.id)?.optBoolean("downloadComplete")==true } ?: return null }
        val parent=File(context.filesDir,"ct2-models").apply { check(isDirectory || mkdirs()) }
        val stage=File(parent,".install-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            spec.files.zip(rows).forEach { (part,row) ->
                job.ensureActive()
                context.contentResolver.openInputStream(downloads.uri(row.id))?.use { input ->
                    ModelIntegrity.stageFile(input,stage,part.name,part.sha256,part.bytes) { job.ensureActive() }
                } ?: error("Cannot open downloaded CT2 component ${part.name}")
            }
            File(stage,MANIFEST).writeText(manifest(spec));job.ensureActive()
            publish(context,spec,stage)
            com.sal7one.transiber.preferences.ModelSourceBindings.recheck(context)
            return spec.id
        } finally { stage.deleteRecursively() }
    }
    fun importZip(context: Context,input: InputStream,checkActive: () -> Unit = {}): Pair<Ct2ModelSpec,File> {
        requireRuntime(context)
        val parent=File(context.filesDir,"ct2-models").apply { check(isDirectory || mkdirs()) }
        val work=File(parent,".import-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val (spec,staged)=com.sal7one.common_jni.translation.Ct2Archive.stage(work,input,checkActive=checkActive)
            checkActive();return spec to publish(context,spec,staged)
        } finally { work.deleteRecursively() }
    }
}
