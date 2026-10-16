package com.sal7one.transiber.tv

import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal enum class PairingPhase { WAITING, REVIEW, SAVING, COMPLETE, EXPIRED, CLOSED }
internal data class PhoneSetupReview(val source: String, val mode: String, val target: String,
    val voice: String, val readAloud: Boolean, val volume: Int, val replacesKey: Boolean)
internal data class PhonePairingState(val phase: PairingPhase, val review: PhoneSetupReview? = null, val error: String? = null)

/** Never include credential contents in generated data-class toString(), notifications or state. */
internal class PhoneSetupProposal(private var secret: String, val review: PhoneSetupReview) {
    fun key(): String = secret
    fun clear() { secret = "" }
    override fun toString() = "PhoneSetupProposal(redacted)"
}

/**
 * Explicit, five-minute LAN setup window. One pending submission, physical TV approval,
 * bounded HTTP parsing, no credentials in URLs/responses/logs and no provider requests.
 * The local page encrypts its submission with a QR-pinned ephemeral RSA key + AES-GCM.
 * This protects payloads on trusted home networks; HTTP page delivery does not defend
 * against an active LAN attacker. Never expose this listener through a router/relay.
 */
internal class PhoneSetupServer(
    address: InetAddress,
    private val assets: (String) -> ByteArray,
    private val defaults: JSONObject,
    private val targets: Set<String>,
    lifetimeMillis: Long = 5 * 60_000L,
) : Closeable {
    init {
        check(ByokPolicy.FEATURE_BYOK) { "Phone network setup is unavailable in the offline build." }
        require(address is java.net.Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress)) { "Phone setup requires a private local address." }
        require(lifetimeMillis in 1..5 * 60_000L)
        require(defaults.keys().asSequence().toSet() == setOf("source", "mode", "target", "voice", "readAloud", "volume", "languages"))
    }
    private val lock = Any()
    private val random = SecureRandom()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private val listener = ServerSocket(0, 2, address)
    val baseUrl = "http://${address.hostAddress}:${listener.localPort}"
    val qrUrl = "$baseUrl/#token=$token&publicKey=" + Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hearth-phone-setup").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "hearth-phone-expiry").apply { isDaemon = true } }
    private var pending: PhoneSetupProposal? = null
    private val seen = mutableSetOf<String>()
    @Volatile private var closed = false
    private val stateValue = MutableStateFlow(PhonePairingState(PairingPhase.WAITING))
    val state = stateValue.asStateFlow()
    private val expiryTask = timer.schedule({ shutdown(PairingPhase.EXPIRED) }, lifetimeMillis, TimeUnit.MILLISECONDS)

    init {
        worker.execute {
            while (!closed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                socket.use { client ->
                    client.soTimeout = 3_000
                    try { handle(client) } catch (_: Exception) {
                        // Malformed network input never becomes an app/engine failure or a credential log.
                        runCatching { respond(client, 400, "text/plain", "Invalid setup request".toByteArray()) }
                    }
                }
            }
        }
    }

    suspend fun approve(save: suspend (PhoneSetupProposal) -> Unit) {
        val proposal = synchronized(lock) {
            check(!closed && stateValue.value.phase == PairingPhase.REVIEW) { "No phone setup is waiting for approval." }
            checkNotNull(pending).also {
                stateValue.value = PhonePairingState(PairingPhase.SAVING, it.review)
            }
        }
        try {
            save(proposal)
            synchronized(lock) {
                proposal.clear(); pending = null
                if (!closed) {
                    stateValue.value = PhonePairingState(PairingPhase.COMPLETE)
                    timer.schedule({ shutdown(PairingPhase.COMPLETE) }, 5, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            synchronized(lock) {
                if (!closed) {
                    stateValue.value = PhonePairingState(PairingPhase.REVIEW, proposal.review, e.message ?: e.javaClass.simpleName)
                }
            }
            throw e
        }
    }

    fun reject() = synchronized(lock) {
        if (stateValue.value.phase != PairingPhase.REVIEW || closed) return@synchronized
        pending?.clear(); pending = null
        stateValue.value = PhonePairingState(PairingPhase.WAITING, error = "Phone setup was rejected. Send again from the phone.")
    }

    override fun close() = shutdown(PairingPhase.CLOSED)
    private fun shutdown(phase: PairingPhase) {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending?.clear(); pending = null
            stateValue.value = PhonePairingState(phase)
        }
        expiryTask.cancel(false)
        runCatching { listener.close() }
        worker.shutdownNow(); timer.shutdownNow()
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private fun readRequest(input: InputStream): Request {
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val b = input.read()
                require(b >= 0 && bytes.size < 2048) { "Incomplete or oversized header" }
                if (b == 10) break
                require(b == 13 || b in 32..126) { "Invalid HTTP header" }
                bytes += b.toByte()
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] == "HTTP/1.1")
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val next = line(); total += next.length
            require(total <= 8192)
            if (next.isEmpty()) break
            val separator = next.indexOf(':'); require(separator > 0)
            val name = next.substring(0, separator).lowercase()
            require(name !in headers)
            headers[name] = next.substring(separator + 1).trim()
        }
        require("transfer-encoding" !in headers)
        val length = headers["content-length"]?.toIntOrNull() ?: if ("content-length" in headers) error("Invalid length") else 0
        require(length in 0..16_384)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) { val n = input.read(body, offset, length - offset); require(n > 0); offset += n }
        return Request(request[0], request[1], headers, body)
    }

    private fun handle(socket: Socket) {
        val req = readRequest(socket.getInputStream())
        if (closed) { respond(socket, 410, "text/plain", "Pairing ended".toByteArray()); return }
        if (req.headers["host"] != baseUrl.removePrefix("http://") ||
            req.headers["origin"]?.let { it != baseUrl } == true) {
            respond(socket, 403, "text/plain", "Request not allowed".toByteArray()); return
        }
        val publicAssets = mapOf("/" to ("index.html" to "text/html; charset=utf-8"),
            "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
            "/crypto.js" to ("crypto.js" to "text/javascript; charset=utf-8"),
            "/style.css" to ("style.css" to "text/css; charset=utf-8"),
            "/forge.min.js" to ("forge.min.js" to "text/javascript; charset=utf-8"))
        if (req.method == "GET" && req.path in publicAssets) {
            val (name, type) = publicAssets.getValue(req.path)
            respond(socket, 200, type, assets(name)); return
        }
        if (!MessageDigest.isEqual(req.headers["x-hearth-pairing"].orEmpty().toByteArray(), token.toByteArray())) {
            respond(socket, 403, "text/plain", "Pairing not authorized".toByteArray()); return
        }
        when {
            req.method == "GET" && req.path == "/api/defaults" -> json(socket, 200, JSONObject(defaults.toString()))
            req.method == "GET" && req.path == "/api/status" -> json(socket, 200,
                JSONObject().put("state", stateValue.value.phase.name.lowercase()))
            req.method == "POST" && req.path == "/api/setup" && req.headers["content-type"] == "application/json" -> {
                synchronized(lock) {
                    if (stateValue.value.phase != PairingPhase.WAITING) {
                        json(socket, 409, JSONObject().put("error", "Finish the current setup on the TV first.")); return
                    }
                    require(seen.size < 8) { "Too many setup attempts. Reopen phone setup." }
                    val envelope = JSONObject(req.body.toString(Charsets.UTF_8))
                    require(envelope.keys().asSequence().toSet() == setOf("v", "wrappedKey", "nonce", "ciphertext"))
                    require(envelope.getInt("v") == 1)
                    val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").run {
                        for (name in listOf("wrappedKey", "nonce", "ciphertext")) update(Base64.getDecoder().decode(envelope.getString(name)))
                        digest()
                    })
                    require(digest !in seen) { "Setup has already been submitted." }
                    val proposal = decrypt(envelope)
                    seen += digest
                    pending = proposal
                    stateValue.value = PhonePairingState(PairingPhase.REVIEW, proposal.review)
                    json(socket, 202, JSONObject().put("state", "review"))
                }
            }
            else -> respond(socket, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun decrypt(envelope: JSONObject): PhoneSetupProposal {
        val decoder = Base64.getDecoder()
        val wrapped = decoder.decode(envelope.getString("wrappedKey")); require(wrapped.size == 256)
        val nonce = decoder.decode(envelope.getString("nonce")); require(nonce.size == 12)
        val ciphertext = decoder.decode(envelope.getString("ciphertext")); require(ciphertext.size in 17..8192)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, keyPair.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val key = rsa.doFinal(wrapped); require(key.size == 32)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(token.toByteArray()); doFinal(ciphertext)
            }
        } finally { key.fill(0) }
        try {
            val value = JSONObject(plaintext.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("key", "source", "mode", "target", "voice", "readAloud", "volume"))
            val secret = value.getString("key").trim()
            require(secret.length <= 1024 && secret.all { it.code in 33..126 }) { "Invalid API key" }
            val source = value.getString("source"); require(source in setOf("PLAYBACK_CAPTURE", "MIC"))
            val mode = value.getString("mode"); require(mode in setOf("CAPTIONS", "TRANSLATE"))
            val target = value.getString("target"); require(target in targets)
            val voice = value.getString("voice"); require(voice in setOf("coral", "alloy", "nova", "onyx"))
            require(value.get("readAloud") is Boolean && value.get("volume") is Int)
            val volume = value.getInt("volume"); require(volume in 0..100)
            return PhoneSetupProposal(secret, PhoneSetupReview(source, mode, target, voice, value.getBoolean("readAloud"), volume, secret.isNotBlank()))
        } finally { plaintext.fill(0) }
    }

    private fun json(socket: Socket, code: Int, value: JSONObject) = respond(socket, code, "application/json", value.toString().toByteArray())
    private fun respond(socket: Socket, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 403 -> "Forbidden"; 409 -> "Conflict"; 410 -> "Gone"; else -> "Not Found" }
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
