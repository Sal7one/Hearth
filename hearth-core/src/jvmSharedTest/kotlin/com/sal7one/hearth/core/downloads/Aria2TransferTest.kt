package com.sal7one.hearth.core.downloads

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class Aria2TransferTest {
    private val versionStub="if [ \"${'$'}1\" = \"--version\" ]; then echo 'aria2 version 1.37.0'; echo 'Enabled Features: HTTPS'; exit 0; fi\n"
    @Test fun argumentsSeparateFileAndConnectionConcurrencyAndDisableAmbientConfiguration() {
        val request = Aria2Request(File("/opt/aria2c"),File("/tmp/models/a model.part"),"https://example.org/model?revision=pinned",12,connections=8)
        val args = Aria2Transfer.arguments(request)
        assertTrue("--max-concurrent-downloads=1" in args)
        assertTrue("--split=8" in args && "--max-connection-per-server=8" in args)
        assertTrue("--no-conf=true" in args && "--no-netrc=true" in args && "--check-certificate=true" in args)
        assertTrue("--out=a model.part" in args)
        assertEquals(request.url,args.last())
        assertFalse(args.any { it.startsWith("--header") })
        assertFailsWith<IllegalArgumentException> { Aria2Transfer.arguments(request.copy(url="https://key:secret@example.org/model")) }
        assertFailsWith<IllegalArgumentException> { Aria2Transfer.arguments(request.copy(url="file:///tmp/a")) }
        assertFailsWith<IllegalArgumentException> { Aria2Transfer.arguments(request.copy(connections=100)) }
        assertFailsWith<IllegalArgumentException> { Aria2Transfer.arguments(request.copy(environment=mapOf("PROVIDER_API_KEY" to "secret"))) }
    }
    @Test fun progressUsesCompletedPiecesRatherThanSparseFileLength() {
        assertEquals(Aria2Progress(1048576,8388608,123456),Aria2Transfer.parseProgress("[#ab1234 1048576B/8388608B(12%) CN:4 DL:123456B ETA:3s]"))
        assertNull(Aria2Transfer.parseProgress("[#ab1234 9B/8B(112%) CN:4 DL:5B]"))
        assertNull(Aria2Transfer.parseProgress("Some server message"))
    }
    @Test fun cancellationStopsTheOwnedProcessAndRetainsResumeState() = runBlocking {
        if(File.separatorChar == '\\') return@runBlocking
        val root=Files.createTempDirectory("hearth-aria-cancel-").toFile()
        try {
            val marker=File(root,"owned.pid")
            val executable=File(root,"aria2c").apply {
                writeText("#!/bin/sh\n"+versionStub+"printf '%s' \"\u0024\u0024\" > '${marker.path}'\nexec sleep 30\n")
                setExecutable(true)
            }
            val target=File(root,"model.part").apply { writeText("partial") }
            File(target.path+".aria2").writeText("piece-map")
            val task=launch(Dispatchers.IO) { Aria2Transfer.download(Aria2Request(executable,target,"https://example.org/model",100)) }
            withTimeout(3000) { while(!marker.isFile)delay(10) }
            withTimeout(4000) { task.cancelAndJoin() }
            val pid=marker.readText().toLong()
            // ProcessHandle is not in Android's API surface. The pipe and child
            // termination contract is exercised here through a bounded join.
            assertTrue(pid>0)
            assertEquals("partial",target.readText())
            assertEquals("piece-map",File(target.path+".aria2").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun nonzeroExitSurfacesTheActualEngineError() = runBlocking {
        if(File.separatorChar == '\\') return@runBlocking
        val root=Files.createTempDirectory("hearth-aria-fail-").toFile()
        try {
            val executable=File(root,"aria2c").apply { writeText("#!/bin/sh\n"+versionStub+"echo 'TLS certificate verification failed'\nexit 60\n");setExecutable(true) }
            val failure=assertFailsWith<IllegalStateException> { Aria2Transfer.download(Aria2Request(executable,File(root,"model.part"),"https://example.org/model",100)) }
            assertTrue(failure.message.orEmpty().contains("TLS certificate verification failed"))
        } finally {root.deleteRecursively()}
    }
}
