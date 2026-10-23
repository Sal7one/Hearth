package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Host coverage of the fresh-process bootstrap contract, without an Activity or JNI binary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BenchmarkWorkerInitializationTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun vosk() = BenchmarkCandidate("vosk-model-ar", "Vosk Arabic", "Vosk / batch",
        setOf("ar"), file = File(app.filesDir, "models/vosk-model-ar"), acceleration = "cpu")

    @Test fun freshWorkerRegistersApplicationContextBeforeTheVoskRouteCanOpen() {
        // An Activity was never constructed. A context wrapper must also resolve
        // to the process-wide application, rather than retain a component context.
        val component = ContextWrapper(app)
        var roots: List<File>? = null
        var stagingRoot: File? = null
        var platformReached = false
        BenchmarkWorkerInitialization.initialize(component, vosk(), commonJni = { context ->
            assertSame(app, context)
            // These are the process-local context bindings consumed by VoskEngine.
            roots = listOf(context.filesDir, context.cacheDir)
            stagingRoot = File(context.cacheDir, "common-jni-models")
        }, platform = { context, candidate ->
            assertEquals(vosk(), candidate)
            assertSame(app, context)
            assertTrue(checkNotNull(roots).any { candidate.file!!.path.startsWith(it.path + File.separator) })
            assertEquals(File(app.cacheDir, "common-jni-models"), stagingRoot)
            platformReached = true
        })
        assertTrue(platformReached)
    }

    @Test fun missingNativeLibraryIsSurfacedBeforeAnyModelOrPlatformClientStarts() {
        val failure = IllegalStateException("Failed to load native library: unsupported ABI")
        var platformReached = false
        val observed = runCatching {
            BenchmarkWorkerInitialization.initialize(app, vosk(), commonJni = { throw failure },
                platform = { _, _ -> platformReached = true })
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertFalse(platformReached)
    }
}
