package com.sal7one.transiber.preferences

import android.content.Context
import com.sal7one.transiber.ocr.OcrCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ModelSourceBindingsRecoveryTest {
    @Test fun corruptSavedBindingCanBeRebuiltFromAnExistingPrivateCopy() = runBlocking {
        val context:Context=RuntimeEnvironment.getApplication()
        val asset=OcrCatalog.detector
        val file=File(context.filesDir,"ocr-models/${asset.id}.onnx")
        file.parentFile!!.mkdirs()
        RandomAccessFile(file,"rw").use { it.setLength(asset.bytes) }
        val store=UserPreferences.store(context,"model-source-bindings")
        try {
            assertTrue(store.edit().putString(asset.id,"{broken saved metadata").commit())
            val recovered=ModelSourceBindings.verifyAtLaunch(context).first { it.modelId == asset.id }
            // Startup does metadata/access checks; real initialization still hashes.
            assertTrue(recovered.error, recovered.available)
            assertEquals(listOf(file.absolutePath),recovered.paths)
            assertNull(recovered.error)
            file.delete()
            val missing=ModelSourceBindings.recheck(context).first { it.modelId == asset.id }
            assertFalse(missing.available)
            assertTrue(missing.error.orEmpty().contains("missing"))
        } finally { file.delete();ModelSourceBindings.forget(context,asset.id) }
    }
}
