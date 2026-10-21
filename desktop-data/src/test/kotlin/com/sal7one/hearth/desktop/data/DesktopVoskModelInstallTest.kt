package com.sal7one.hearth.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner opt-in only: exercises the real ZIP install path and the model-tree pin for
 * Vosk. This downloads the pinned 41 MB English ZIP once.
 *
 * `HEARTH_VOSK_INSTALL_SMOKE=1 ./gradlew -PhearthDesktopOnly=true :desktop-data:test --tests "*DesktopVoskModelInstallTest*"`
 */
class DesktopVoskModelInstallTest {
    @Test
    fun installsAndVerifiesThePinnedVoskZip() = runBlocking {
        assumeTrue("Set HEARTH_VOSK_INSTALL_SMOKE=1", System.getenv("HEARTH_VOSK_INSTALL_SMOKE") == "1")
        val directory = Files.createTempDirectory("hearth-vosk-install-")
        try {
            val library = DesktopModelLibrary(directory)
            val selection = library.download("vosk-model-small-en-us-0.15")
            assertEquals("vosk-local", selection.engineId)
            val root = Path.of(selection.modelPath)
            assertTrue(Files.isDirectory(root), selection.modelPath)
            assertTrue(Files.isRegularFile(root.resolve("conf/mfcc.conf")))
            assertEquals("1855931c4d84fa0f731a5a7906c1356837335d49ee7f2ca6a6a32389bac7f8e8", selection.options["treeSha256"])
            val verified = library.verify("vosk-model-small-en-us-0.15", root)
            assertEquals(selection.modelPath, verified.modelPath)
            assertTrue(library.installations.value.getValue("vosk-model-small-en-us-0.15").ready)
            println("VOSK_INSTALL_SMOKE path=$root installed=${library.installations.value["vosk-model-small-en-us-0.15"]?.path}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
