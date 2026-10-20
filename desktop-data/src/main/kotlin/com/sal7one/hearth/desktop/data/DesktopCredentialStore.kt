package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.CredentialResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** macOS Keychain and Windows current-user DPAPI. Plaintext is never written to preferences/setup JSON. */
class DesktopCredentialStore(private val directory: Path) : CredentialResolver {
    private val os = System.getProperty("os.name").lowercase()
    val storageDescription: String = when {
        "mac" in os -> "macOS Keychain"
        "win" in os -> "Windows user-protected credentials (DPAPI)"
        else -> "Session only on this operating system"
    }
    private val session = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun put(id: String, secret: String): Unit = withContext(Dispatchers.IO) {
        validateId(id); require(secret.isNotBlank() && secret.length <= 16_384) { "Invalid credential length" }
        when {
            "mac" in os -> {
                // Only the reference is an argument; the native Security.framework bridge reads the secret from stdin.
                val result = run(listOf(keychainHelper().toString(), "put", id), secret)
                check(result.first == 0) { "macOS Keychain could not save this credential (status ${result.first})" }
            }
            "win" in os -> {
                Files.createDirectories(directory.resolve("credentials"))
                val payload = Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8))
                val result = powershell("Add-Type -AssemblyName System.Security; \$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); [Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect(\$b,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))", payload)
                check(result.first == 0 && result.second.matches(Regex("[A-Za-z0-9+/=]+"))) { "Windows could not protect this credential" }
                DesktopSettingsStore.writeAtomic(file(id), result.second)
            }
        }
        session[id] = secret
    }
    suspend fun remove(id: String): Unit = withContext(Dispatchers.IO) {
        validateId(id)
        when {
            "mac" in os -> {
                val status = run(listOf(keychainHelper().toString(), "remove", id)).first
                check(status == 0 || status == 44) { "macOS Keychain could not remove this credential (status $status)" }
            }
            "win" in os -> Files.deleteIfExists(file(id))
        }
        session.remove(id)
        Unit
    }
    override suspend fun resolve(credentialId: String): String? = withContext(Dispatchers.IO) {
        if (credentialId.isBlank()) return@withContext null
        if (credentialId.startsWith("env:")) {
            val name = credentialId.removePrefix("env:")
            require(name.matches(Regex("[A-Z_][A-Z0-9_]{0,100}"))) { "Invalid environment credential reference" }
            return@withContext System.getenv(name)?.takeIf(String::isNotBlank)
        }
        validateId(credentialId)
        session[credentialId]?.let { return@withContext it }
        val secret = when {
            "mac" in os -> {
                val result = run(listOf(keychainHelper().toString(), "get", credentialId))
                when (result.first) { 0 -> result.second.removeSuffix("\n"); 44 -> null
                    else -> error("macOS Keychain could not read this credential (status ${result.first})") }
            }
            "win" in os && Files.isRegularFile(file(credentialId)) -> {
                val encrypted = Files.readString(file(credentialId)).also { require(it.length <= 64 * 1024) }
                val result = powershell("Add-Type -AssemblyName System.Security; \$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); [Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))", encrypted)
                check(result.first == 0) { "Windows could not unlock this credential for the current user" }
                String(Base64.getDecoder().decode(result.second), Charsets.UTF_8)
            }
            else -> null
        }
        secret?.also { session[credentialId] = it }
    }
    private fun file(id: String): Path = directory.resolve("credentials").resolve(
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".dpapi")
    private fun validateId(id: String) { require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid credential reference" } }
    private fun powershell(script: String, stdin: String): Pair<Int, String> = run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), stdin)
    private fun run(command: List<String>, stdin: String = ""): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 64 * 1024) { process.destroyForcibly(); break }
                    output.write(buffer, 0, count)
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(1000); error("Operating system credential service timed out") }
        reader.join(2000)
        check(!reader.isAlive) { "Credential service did not close its output" }
        return process.exitValue() to output.toString(Charsets.UTF_8).trimEnd('\r', '\n')
    }
    @Synchronized private fun keychainHelper(): Path {
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
        val resource = "/media/macos-$arch/hearth-keychain"
        val bytes = DesktopCredentialStore::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("The macOS Keychain bridge is not installed. Build scripts/desktop/build-media.sh before packaging Hearth.")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val parent = directory.resolve("platform/keychain/$hash")
        val destination = parent.resolve("hearth-keychain")
        Files.createDirectories(parent)
        Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        if (!Files.isRegularFile(destination) || !MessageDigest.isEqual(Files.readAllBytes(destination), bytes)) {
            val temporary = Files.createTempFile(parent, ".keychain-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return destination
    }

}
