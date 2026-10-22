package com.sal7one.common_jni

import android.os.Build
import android.os.Process
import com.sal7one.hearth.tts.ming.MingCpu

/**
 * Android binding over the pinned Ming-3.0-tts-0.5B JNI (`common-jni/src/main/cpp/ming/jni`).
 *
 * The desktop module has its own wrapper over the same C ABI; this module is independent and
 * never links `desktop-runtime`. One synthesis loads the ~850 MB bundle, generates the whole
 * utterance and writes a 44.1 kHz mono PCM16 WAV, so call [synthesizeWav] from a worker thread.
 * The JNI exposes no streaming and no native cancellation.
 */
object MingNative {
    data class Availability(val loaded: Boolean, val library: String?, val dotprod: Boolean, val reason: String)

    /** Loading once per process is enough; failures are reported instead of thrown at class init. */
    val availability: Availability = load()

    external fun abiVersion(): Int
    external fun modelName(): String

    /** Blocking. Returns the native stats JSON line; call from a worker thread. */
    external fun synthesizeWav(
        bundleDir: String, text: String, voice: String, outWav: String,
        preset: Int, seed: Long, threads: Int,
    ): String

    private fun load(): Availability {
        val abi = runCatching { Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull()
        val is64 = runCatching { Process.is64Bit() }.getOrNull()
        if (!MingCpu.supports(abi, is64 == true)) {
            return Availability(
                loaded = false,
                library = null,
                dotprod = false,
                reason = "Ming local TTS requires a 64-bit arm64-v8a app process; this process reports " +
                    "${abi ?: "an unknown ABI"}${if (is64 == true) "" else " (32-bit or unknown)"}. " +
                    "Install the arm64-v8a APK.",
            )
        }
        val cpuInfo = runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrNull()
        val dotprod = MingCpu.advertisesDotprod(cpuInfo)
        val failures = mutableListOf<String>()
        for (name in MingCpu.libraryOrder(abi, is64 == true, cpuInfo)) {
            try {
                System.loadLibrary(name)
                return Availability(
                    loaded = true,
                    library = name,
                    dotprod = dotprod,
                    reason = "Ming JNI $name loaded (${if (name == MingCpu.PRIMARY_LIBRARY) "asimddp" else "scalar"} build)",
                )
            } catch (error: UnsatisfiedLinkError) {
                failures += "$name: ${error.message}"
            } catch (error: SecurityException) {
                failures += "$name: ${error.message}"
            }
        }
        return Availability(
            loaded = false,
            library = null,
            dotprod = dotprod,
            reason = "Ming JNI is not packaged for this app process: " +
                failures.joinToString("; ").ifEmpty { "no library candidate" },
        )
    }
}
