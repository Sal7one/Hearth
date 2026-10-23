package com.sal7one.transiber.downloads

import android.content.Context
import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Play-only, source-built executable in the package's executable native directory. */
internal object Aria2Backend {
    private val mutex = Mutex()
    private var cached: Pair<String,Aria2Runtime>? = null
    private fun executable(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libhearth_aria2c.so")
    fun available(context: Context): Boolean = ByokPolicy.FEATURE_BYOK && executable(context).let { it.isFile && it.canExecute() }
    suspend fun prepare(context: Context): Aria2Runtime? = withContext(Dispatchers.IO) {
        check(ByokPolicy.FEATURE_BYOK) { "Downloads are disabled in the offline build" }
        if (!available(context)) return@withContext null
        mutex.withLock {
            val binary = executable(context)
            val manifestText=context.assets.open("downloads/aria2-runtime.json").bufferedReader().use { it.readText() }
            val identity="${binary.canonicalPath}:${binary.length()}:${binary.lastModified()}:$manifestText"
            cached?.takeIf { it.first == identity }?.let { return@withLock it.second }
            val machine = binary.inputStream().use { input ->
                val header = ByteArray(20)
                check(input.read(header) == 20 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) { "Invalid aria2 executable" }
                (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            }
            val abi = when(machine) {183 -> "arm64-v8a"; 62 -> "x86_64"; else -> error("Unsupported aria2 executable ABI")}
            val manifest = JSONObject(manifestText)
            val pin = manifest.getJSONObject("binaries").getJSONObject(abi)
            check(binary.length() == pin.getLong("bytes")) { "aria2 executable size differs from its build pin" }
            val sha = binary.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
                while(true) {val n=input.read(buffer); if(n<0)break;digest.update(buffer,0,n)}
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            check(sha == pin.getString("sha256")) { "aria2 executable integrity check failed" }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }.distinctBy { it.serialNumber to it.subjectX500Principal }
            check(trust.isNotEmpty()) { "Android's trusted certificate store is empty" }
            val directory = File(context.noBackupFilesDir,"aria2").apply { check(isDirectory || mkdirs()) }
            val certificates = File(directory,"android-trust.pem")
            val temporary = File.createTempFile("certificates-", ".part", directory)
            try {
                temporary.bufferedWriter().use { out -> trust.forEach {
                    out.appendLine("-----BEGIN CERTIFICATE-----")
                    out.appendLine(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded))
                    out.appendLine("-----END CERTIFICATE-----")
                } }
                Files.move(temporary.toPath(),certificates.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            Aria2Runtime(binary, mapOf("SSL_CERT_FILE" to certificates.absolutePath)).also { cached=identity to it }
        }
    }
}
