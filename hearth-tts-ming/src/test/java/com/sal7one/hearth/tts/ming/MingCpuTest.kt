package com.sal7one.hearth.tts.ming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MingCpuTest {
    private val dotprodCpu = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x41
    """.trimIndent()

    private val scalarCpu = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics
    """.trimIndent()

    @Test fun detectsTheDotprodFlagOnlyInArm64FeatureLines() {
        assertTrue(MingCpu.advertisesDotprod(dotprodCpu))
        assertTrue(MingCpu.advertisesDotprod("Features: asimddp\n"))
        assertTrue(MingCpu.advertisesDotprod("features: fp ASIMDDP\n"))
        assertFalse(MingCpu.advertisesDotprod(scalarCpu))
        assertFalse(MingCpu.advertisesDotprod(null))
        assertFalse(MingCpu.advertisesDotprod(""))
        // x86 uses a "flags" line; this module only ships arm64 ELF files.
        assertFalse(MingCpu.advertisesDotprod("flags\t\t: sse4_2 asimddp\n"))
    }

    @Test fun onlyA64BitArm64ProcessCanUseTheStagedLibraries() {
        assertTrue(MingCpu.supports("arm64-v8a", true))
        assertFalse(MingCpu.supports("arm64-v8a", false))
        assertFalse(MingCpu.supports("armeabi-v7a", true))
        assertFalse(MingCpu.supports("x86_64", true))
        assertFalse(MingCpu.supports(null, true))
    }

    @Test fun dotprodIsPreferredButUnknownOrScalarDevicesNeverLoadIt() {
        assertEquals(
            listOf(MingCpu.PRIMARY_LIBRARY, MingCpu.SCALAR_LIBRARY),
            MingCpu.libraryOrder("arm64-v8a", true, dotprodCpu),
        )
        assertEquals(
            listOf(MingCpu.SCALAR_LIBRARY),
            MingCpu.libraryOrder("arm64-v8a", true, scalarCpu),
        )
        assertEquals(
            listOf(MingCpu.SCALAR_LIBRARY),
            MingCpu.libraryOrder("arm64-v8a", true, null),
        )
        assertEquals(emptyList<String>(), MingCpu.libraryOrder("armeabi-v7a", true, dotprodCpu))
    }
}
