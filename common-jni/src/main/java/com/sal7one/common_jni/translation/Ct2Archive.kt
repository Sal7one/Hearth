package com.sal7one.common_jni.translation

import com.sal7one.common_jni.model.ModelIntegrity
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Pinned import format: flat files and a non-executable identity manifest at archive root. */
object Ct2Archive {
    const val MANIFEST = "hearth-translation.json"
    fun stage(parent: File,input: InputStream,models: List<Ct2ModelSpec> = Ct2Catalog.models,
        checkActive: () -> Unit = {}): Pair<Ct2ModelSpec,File> {
        val staged=try { ModelIntegrity.stageDirectory(parent,"package") { sink ->
            ZipInputStream(input).use { zip ->
                var count=0;var bytes=0L
                while(true) {
                    checkActive();val entry=zip.nextEntry ?: break
                    require(++count<=16) { "CT2 archive exceeds 16 entries" }
                    require(!entry.isDirectory && !entry.name.contains('/') && !entry.name.contains('\\')) { "CT2 files and manifest must be at archive root" }
                    sink.addFile(entry.name,object: java.io.FilterInputStream(zip) {
                        override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                            checkActive();val n=super.read(buffer,offset,length)
                            if(n>0) { bytes+=n;require(bytes<=500_000_000L) { "CT2 archive exceeds 500 MB" } }
                            return n
                        }
                    });zip.closeEntry()
                }
            }
        }.file } catch(e: Exception) {
            val cancelled=generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.util.concurrent.CancellationException>().firstOrNull()
            if(cancelled!=null)throw cancelled
            throw e
        }
        try {
            val manifest=File(staged,MANIFEST);require(manifest.length() in 1..16384) { "Missing or oversized $MANIFEST" }
            val json=JSONObject(manifest.readText());require(json.keys().asSequence().all { it in setOf("schemaVersion","modelId") }) { "Unknown CT2 manifest field" };require(json.getInt("schemaVersion")==1) { "Unsupported CT2 package schema" }
            val spec=models.firstOrNull { it.id==json.getString("modelId") } ?: error("Unknown CT2 model identity")
            require(staged.listFiles().orEmpty().map { it.name }.toSet()==spec.files.map { it.name }.toSet()+MANIFEST) { "CT2 archive must contain exactly its required model components" }
            checkActive();spec.verify(staged);return spec to staged
        } catch(e: Throwable) { staged.deleteRecursively();throw e }
    }
}
