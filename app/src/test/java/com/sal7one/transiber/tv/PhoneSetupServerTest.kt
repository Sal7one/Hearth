package com.sal7one.transiber.tv

import com.sal7one.transiber.byok.ByokPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class PhoneSetupServerTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private fun defaults() = JSONObject().put("source", "MIC").put("mode", "TRANSLATE").put("target", "ar")
        .put("voice", "coral").put("readAloud", false).put("volume", 70).put("languages", JSONArray())
    private fun server(ttl: Long = 60_000) = PhoneSetupServer(address, { "static asset".toByteArray() }, defaults(), setOf("en", "ar"), ttl)
    private fun token(server: PhoneSetupServer) = URI(server.qrUrl).fragment.substringAfter("token=").substringBefore('&')
    private fun payload() = JSONObject().put("key", "unit-test-phone-key").put("source", "MIC").put("mode", "TRANSLATE")
        .put("target", "ar").put("voice", "nova").put("readAloud", true).put("volume", 70)
    private fun encrypted(server: PhoneSetupServer, value: JSONObject = payload()): JSONObject {
        val public = URI(server.qrUrl).fragment.substringAfter("publicKey=")
        val rsaKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(public)))
        val aes = ByteArray(32).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, rsaKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)); doFinal(aes)
        }
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(token(server).toByteArray()); doFinal(value.toString().toByteArray())
        }
        return JSONObject().put("v", 1).put("wrappedKey", Base64.getEncoder().encodeToString(wrapped))
            .put("nonce", Base64.getEncoder().encodeToString(nonce)).put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
    }
    private data class Response(val code: Int, val body: String)
    private fun request(server: PhoneSetupServer, path: String, body: String? = null, authorization: String = token(server),
                        host: String = URI(server.baseUrl).authority, origin: String = server.baseUrl): Response {
        val uri = URI(server.baseUrl)
        return Socket(address, uri.port).use { socket ->
            socket.soTimeout = 5000
            val bytes = body.orEmpty().toByteArray()
            socket.getOutputStream().apply {
                write("${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\nX-Hearth-Pairing: $authorization\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()); write(bytes); flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            Response(raw.substringBefore("\r\n").split(' ')[1].toInt(), raw.substringAfter("\r\n\r\n"))
        }
    }
    private fun offline(): Boolean {
        if (ByokPolicy.FEATURE_BYOK) return false
        assertThrows(IllegalStateException::class.java) { server() }
        return true
    }

    @Test fun nothingSavesUntilTvApprovalAndKeysNeverAppearInResponsesOrState() = runBlocking {
        if (offline()) return@runBlocking
        server().use { server ->
            assertFalse(server.qrUrl.contains("unit-test-phone-key"))
            assertEquals(403, request(server, "/api/defaults", authorization = "incorrect").code)
            assertEquals(403, request(server, "/", origin = "https://foreign.example").code)
            assertEquals(403, request(server, "/", host = "rebinding.example").code)
            assertEquals(200, request(server, "/api/defaults").code)
            val body = encrypted(server).toString()
            assertFalse(body.contains("unit-test-phone-key"))
            assertEquals(202, request(server, "/api/setup", body).code)
            assertEquals(PairingPhase.REVIEW, server.state.value.phase)
            assertFalse(server.state.value.toString().contains("unit-test-phone-key"))
            assertFalse(request(server, "/api/status").body.contains("unit-test-phone-key"))
            assertEquals(409, request(server, "/api/setup", body).code)
            var saves = 0
            var approved: PhoneSetupProposal? = null
            server.approve { proposal -> assertEquals("unit-test-phone-key", proposal.key()); approved = proposal; saves++ }
            assertEquals(1, saves)
            assertEquals("", approved!!.key())
            assertEquals(PairingPhase.COMPLETE, server.state.value.phase)
            assertEquals(409, request(server, "/api/setup", body).code)
            try { server.approve { saves++ }; fail("Duplicate approval accepted") } catch (_: IllegalStateException) { }
            assertEquals(1, saves)
        }
    }

    @Test fun rejectClearsPendingAndReplayCannotBypassUsingDifferentJsonFormatting() {
        if (offline()) return
        server().use { server ->
            val envelope = encrypted(server)
            assertEquals(202, request(server, "/api/setup", envelope.toString()).code)
            server.reject()
            assertEquals(PairingPhase.WAITING, server.state.value.phase)
            assertNull(server.state.value.review)
            assertEquals(400, request(server, "/api/setup", envelope.toString(2)).code)
            assertEquals(202, request(server, "/api/setup", encrypted(server).toString()).code)
        }
    }

    @Test fun malformedCiphertextOversizedInputAndUnknownSetupFieldsAreRejected() {
        if (offline()) return
        server().use { server ->
            val corrupted = encrypted(server).put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(32)))
            assertEquals(400, request(server, "/api/setup", corrupted.toString()).code)
            try { assertEquals(400, request(server, "/api/setup", "x".repeat(16_385)).code) }
            catch (_: java.net.SocketException) { /* Oversized bodies may be rejected by closing the connection. */ }
            assertEquals(400, request(server, "/api/setup", encrypted(server, payload().put("endpoint", "https://foreign.example")).toString()).code)
            assertEquals(400, request(server, "/api/setup", encrypted(server, payload().put("target", "unsupported")).toString()).code)
            assertEquals(PairingPhase.WAITING, server.state.value.phase)
        }
    }

    @Test fun expiryClosesTheListenerAndDiscardsPendingApproval() {
        if (offline()) return
        server(1000).use { server ->
            assertEquals(202, request(server, "/api/setup", encrypted(server).toString()).code)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (server.state.value.phase != PairingPhase.EXPIRED && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(PairingPhase.EXPIRED, server.state.value.phase)
            assertNull(server.state.value.review)
            assertThrows(java.net.ConnectException::class.java) { Socket(address, URI(server.baseUrl).port).close() }
        }
    }
}
