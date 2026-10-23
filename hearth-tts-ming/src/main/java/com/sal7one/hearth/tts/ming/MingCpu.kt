package com.sal7one.hearth.tts.ming

/**
 * Pure ABI/CPU-feature decisions for the staged Ming JNI. Host tests cover this without loading
 * an Android ELF; [com.sal7one.common_jni.MingNative] supplies the real device values.
 */
internal object MingCpu {
    const val DOTPROD_FLAG = "asimddp"
    const val PRIMARY_LIBRARY = "ming_jni"
    const val SCALAR_LIBRARY = "ming_jni_scalar"

    /** The staged libraries are arm64-v8a only, and the app must be a 64-bit process. */
    fun supports(abi: String?, is64Bit: Boolean): Boolean = abi == "arm64-v8a" && is64Bit

    /**
     * Android exposes CPU features through `/proc/cpuinfo` `Features:` lines. Inconclusive input
     * must select the scalar library: the dotprod build may use instructions the CPU lacks.
     */
    fun advertisesDotprod(cpuInfo: String?): Boolean = cpuInfo.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.substringBefore(':').trim().equals("Features", ignoreCase = true) }
        .flatMap { line -> line.substringAfter(':', "").trim().split(' ', '\t').asSequence() }
        .any { it.equals(DOTPROD_FLAG, ignoreCase = true) }

    /**
     * Ordered candidates. A dotprod device tries the faster build first and may still fall back if
     * dlopen fails; a scalar or unknown device never attempts the dotprod build.
     */
    fun libraryOrder(abi: String?, is64Bit: Boolean, cpuInfo: String?): List<String> =
        if (!supports(abi, is64Bit)) emptyList()
        else if (advertisesDotprod(cpuInfo)) listOf(PRIMARY_LIBRARY, SCALAR_LIBRARY)
        else listOf(SCALAR_LIBRARY)
}
