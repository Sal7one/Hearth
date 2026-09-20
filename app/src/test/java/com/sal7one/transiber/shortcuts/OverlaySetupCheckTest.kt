package com.sal7one.transiber.shortcuts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.io.File

class OverlaySetupCheckTest {
    @Test fun gathersAllFailuresAndKeepsActualErrorsAndFixDestinations() = runTest {
        val report = checkOverlaySetup(listOf(
            SetupProbe("OCR", SetupFix.CAMERA) { error("Missing meiki reader") },
            SetupProbe("Translation", SetupFix.TRANSLATION) { error("Unsupported ru → ja") },
            SetupProbe("Overlay", SetupFix.OVERLAY_PERMISSION) { "Allowed" },
        ))
        assertFalse(report.ready)
        assertEquals(listOf("Missing meiki reader", "Unsupported ru → ja", "Allowed"), report.map { it.detail })
        assertEquals(SetupFix.CAMERA, report.first().fix)
        assertTrue(report.last().passed)
    }
    @Test fun emptyOrNativeFailureCannotPassSetup() = runTest {
        assertFalse(emptyList<SetupResult>().ready)
        val report = checkOverlaySetup(listOf(SetupProbe("Runtime", SetupFix.MODELS) { throw UnsatisfiedLinkError("missing runtime.so") }))
        assertFalse(report.ready)
        assertEquals("missing runtime.so", report.single().detail)
    }
    @Test fun allReadyPermitsThePermissionFlow() = runTest {
        assertTrue(checkOverlaySetup(listOf(SetupProbe("Model", SetupFix.MODELS) { "Installed" })).ready)
    }
    @Test fun cancellationNeverBecomesADiagnosticOrContinuesProbing() = runTest {
        var later = false
        try {
            checkOverlaySetup(listOf(SetupProbe("Model", SetupFix.MODELS) { throw CancellationException("leaving") },
                SetupProbe("Next", SetupFix.CAMERA) { later = true; "wrong" }))
            fail("Expected cancellation")
        } catch (e: CancellationException) { assertEquals("leaving", e.message) }
        assertFalse(later)
    }
    private fun withModel(block: (File) -> Unit) {
        val root = Files.createTempDirectory("shortcut-model").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun manifest(root: File, path: String = "weights.bin", bytes: Int = 3) {
        File(root, "hearth-speech.json").writeText(JSONObject().put("files", org.json.JSONArray().put(JSONObject().put("path", path).put("bytes", bytes))).toString())
    }
    @Test fun presentFilesPassButMissingOrTruncatedFilesFail() = withModel { root ->
        manifest(root); File(root, "weights.bin").writeBytes(byteArrayOf(1,2,3))
        checkSpeechAssetPresence(root)
        File(root, "weights.bin").writeBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { checkSpeechAssetPresence(root) }
        File(root, "weights.bin").delete()
        assertThrows(IllegalArgumentException::class.java) { checkSpeechAssetPresence(root) }
    }
    @Test fun traversalAndLinksAreNotTreatedAsInstalledModels() = withModel { root ->
        manifest(root, "../weights.bin")
        assertThrows(IllegalArgumentException::class.java) { checkSpeechAssetPresence(root) }
        File(root, "real.bin").writeBytes(byteArrayOf(1,2,3))
        Files.createSymbolicLink(File(root, "weights.bin").toPath(), File(root,"real.bin").toPath())
        manifest(root)
        assertThrows(IllegalArgumentException::class.java) { checkSpeechAssetPresence(root) }
    }
}
