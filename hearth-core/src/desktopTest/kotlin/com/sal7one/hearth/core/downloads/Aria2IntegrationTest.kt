package com.sal7one.hearth.core.downloads

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.*

/** Opt-in real executable/TLS/Range test. No model host, owner trust changes or phone access. */
class Aria2IntegrationTest {
    @Test fun actualExecutableDownloadsPausesAndResumesPinnedBytes() = runBlocking {
        val executable=System.getenv("HEARTH_ARIA2_EXECUTABLE")?.let(::File) ?: return@runBlocking
        val root=Files.createTempDirectory("hearth-aria-tls-").toFile()
        val executor=Executors.newFixedThreadPool(8)
        var server:HttpsServer?=null
        try {
            val key=File(root,"fixture.p12")
            val tool=File(System.getProperty("java.home"),"bin/keytool")
            val generate=ProcessBuilder(tool.path,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-storetype","PKCS12","-keystore",key.path,"-storepass","hearth-test","-keypass","hearth-test",
                "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(generate.waitFor()==0)
            val store=KeyStore.getInstance("PKCS12").apply { key.inputStream().use { load(it,"hearth-test".toCharArray()) } }
            val manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"hearth-test".toCharArray()) }
            val tls=SSLContext.getInstance("TLS").apply { init(manager.keyManagers,null,null) }
            val certificate=File(root,"ca.pem").apply { writeText("-----BEGIN CERTIFICATE-----\n"+
                Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(store.getCertificate("fixture").encoded)+"\n-----END CERTIFICATE-----\n") }
            val bytes=ByteArray(8*1024*1024) { (it*31).toByte() }
            val ranges=CopyOnWriteArrayList<String>()
            val sent=AtomicLong()
            server=HttpsServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
                httpsConfigurator=HttpsConfigurator(tls);this.executor=executor
                createContext("/model") { exchange ->
                    val range=exchange.requestHeaders.getFirst("Range")
                    if(range!=null) ranges+=range
                    val parts=range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start=parts?.groupValues?.get(1)?.toInt()?:0
                    val end=parts?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toInt()?.coerceAtMost(bytes.lastIndex)?:bytes.lastIndex
                    exchange.responseHeaders.add("Accept-Ranges","bytes");exchange.responseHeaders.add("ETag","\"pinned-fixture\"")
                    if(parts!=null) exchange.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
                    try {
                        exchange.sendResponseHeaders(if(parts!=null)206 else 200,(end-start+1).toLong())
                        exchange.responseBody.use { output ->
                            var position=start
                            while(position<=end) { val count=minOf(16384,end-position+1);output.write(bytes,position,count);sent.addAndGet(count.toLong());position+=count }
                        }
                    } catch (_:java.io.IOException) { /* Expected when the client pauses. */ }
                    finally { exchange.close() }
                }
                start()
            }
            val target=File(root,"model.part")
            val progress=AtomicLong()
            val request=Aria2Request(executable,target,"https://127.0.0.1:${server.address.port}/model",bytes.size.toLong(),
                connections=4,bandwidthBytesPerSecond=512*1024,environment=mapOf("SSL_CERT_FILE" to certificate.path))
            val task=launch(Dispatchers.IO) { Aria2Transfer.download(request) { progress.set(it.downloadedBytes) } }
            withTimeout(15000) { while(progress.get()<2*1024*1024)delay(50) }
            withTimeout(4000) { task.cancelAndJoin() }
            assertTrue(File(target.path+".aria2").isFile,"Pause must retain aria2 piece state")
            val result=withTimeout(20000) { Aria2Transfer.download(request.copy(bandwidthBytesPerSecond=0)) }
            assertTrue(result.resumed)
            assertNull(result.transferredBytes,"Sparse resume must not invent a network-byte measurement")
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes),MessageDigest.getInstance("SHA-256").digest(target.readBytes()))
            assertFalse(File(target.path+".aria2").exists())
            assertTrue(ranges.any { Regex("bytes=[1-9][0-9]*-").containsMatchIn(it) },"Real requests must use nonzero ranges")
            val report=File("build/reports/downloads/aria2-integration.json").apply { parentFile.mkdirs() }
            report.writeText("""{"tlsVerified":true,"sha256Verified":true,"pauseResumeVerified":true,"rangeRequests":${ranges.size},"fixtureBytes":${bytes.size},"serverWrittenBytes":${sent.get()},"resumedElapsedMillis":${result.elapsedMillis},"resumedNetworkBytes":null}"""+"\n")
        } finally {server?.stop(0);executor.shutdownNow();root.deleteRecursively()}
    }
}
