package com.sal7one.transiber.voice
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer
import org.json.JSONObject
class RemoteVoiceProtocolTest {
    private val raw="""{"model":"qwen-base","label":"Qwen","languages":["en","zh"],"voices":["reference"]}"""
    @Test fun requestHonorsCapabilitiesAndKeepsKeyOutOfUrlAndJson() {
        val caps=RemoteVoiceProtocol.capabilities(raw)
        val request=RemoteVoiceProtocol.request("https://voice.example/api","test-token","hello","en","reference",caps)
        assertEquals("https://voice.example/api/speech",request.url.toString())
        assertEquals("Bearer test-token",request.header("Authorization"))
        val body=Buffer().also {request.body!!.writeTo(it)}.readUtf8()
        assertFalse(body.contains("test-token"));assertEquals("en",JSONObject(body).getString("language"))
        assertFalse(RemoteVoiceConnection("https://voice.example","test-token",caps).toString().contains("test-token"))
        assertThrows(IllegalArgumentException::class.java){RemoteVoiceProtocol.request("https://voice.example","","مرحبا","ar","reference",caps)}
    }
    @Test fun rejectsUnsafeEndpointsHeadersAndUnadvertisedVoices() {
        for(url in listOf("http://localhost:1234","https://key@host","https://host?key=x","https://host#key"))
            assertThrows(IllegalArgumentException::class.java){RemoteVoiceProtocol.endpoint(url)}
        assertThrows(IllegalArgumentException::class.java){RemoteVoiceProtocol.request("https://host","key\r\nother: evil")}
        assertThrows(IllegalArgumentException::class.java){RemoteVoiceProtocol.capabilities(raw.replace("\"en\",\"zh\"","\"anything!\""))}
        assertThrows(IllegalArgumentException::class.java){RemoteVoiceProtocol.request("https://host","","text","en","invented",RemoteVoiceProtocol.capabilities(raw))}
    }
}
