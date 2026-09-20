package com.sal7one.transiber.voice

import com.sal7one.transiber.byok.ByokPolicy
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RemoteVoiceCapabilities(val model: String,val label: String,val languages: Set<String>,val voices: List<String>)
internal data class RemoteVoiceConnection(val endpoint: String,val key: String,val capabilities: RemoteVoiceCapabilities) {
    override fun toString()="RemoteVoiceConnection(model=${capabilities.model}, key=<redacted>)"
}
/** Small explicit protocol for user-hosted models; no assumed universal language support. */
internal object RemoteVoiceProtocol {
    fun endpoint(value: String): HttpUrl = value.trim().toHttpUrlOrNull()?.also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null){"Voice server must be an HTTPS URL without credentials, query or fragment"}
    } ?: error("Enter a valid HTTPS voice server URL")
    fun sameEndpoint(first: String, second: String): Boolean = runCatching {
        endpoint(first).toString().trimEnd('/') == endpoint(second).toString().trimEnd('/')
    }.getOrDefault(false)
    fun capabilities(raw: String): RemoteVoiceCapabilities {
        val json=JSONObject(raw)
        fun strings(name: String,max: Int)=json.getJSONArray(name).let {a ->require(a.length() in 1..max);(0 until a.length()).map {a.getString(it).also {v ->require(v.length in 1..120 && v.none(Char::isISOControl))}}}
        val langs=strings("languages",200).toSet();require(langs.all {it.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))}){"Voice server advertised an invalid language"}
        return RemoteVoiceCapabilities(json.getString("model").also {require(it.length in 1..150 && it.none(Char::isISOControl))},json.getString("label").also {require(it.length in 1..150 && it.none(Char::isISOControl))},langs,strings("voices",100))
    }
    fun request(base: String,key: String,text: String?=null,language: String="",voice: String="",caps: RemoteVoiceCapabilities?=null): Request {
        require(key.none {it=='\r'||it=='\n'}){"Invalid voice API key"}
        val url=endpoint(base).newBuilder().addPathSegment(if(text==null)"capabilities" else "speech").build()
        return Request.Builder().url(url).apply {
            if(key.isNotBlank())header("Authorization","Bearer $key")
            if(text!=null){require(text.isNotBlank() && text.length<=5000);require(caps!=null && language in caps.languages && voice in caps.voices){"The selected voice server does not advertise this language or voice"};post(JSONObject().put("text",text).put("language",language).put("voice",voice).put("model",caps.model).toString().toRequestBody("application/json".toMediaType()))}
        }.build()
    }
}
internal class RemoteVoiceClient(private val enabled: Boolean = ByokPolicy.FEATURE_BYOK) : AutoCloseable {
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val lock=Any()
    private var closed=false
    private var active: Call?=null
    suspend fun execute(request: Request,key: String="",audio: Boolean=false): ByteArray {
        check(enabled){"Network voices are unavailable in the offline build"}
        val call=synchronized(lock){check(!closed){"Voice connection is closed"};check(active==null){"Voice request already in progress"};client.newCall(request).also {active=it}}
        return try {suspendCancellableCoroutine {continuation ->
            continuation.invokeOnCancellation {call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call: Call,e: IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                override fun onResponse(call: Call,response: Response){
                    try {response.use {r ->
                        val bound=if(r.isSuccessful && audio)12*1024*1024 else 65536
                        val body=r.body ?: error("Voice server returned no body")
                        require(body.contentLength()<=bound){"Voice response exceeds size limit"}
                        val out=java.io.ByteArrayOutputStream();body.byteStream().use {input ->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=bound){"Voice response exceeds size limit"};out.write(b,0,n)}}
                        val bytes=out.toByteArray()
                        if(!r.isSuccessful)error("Voice server HTTP ${r.code}: "+bytes.toString(Charsets.UTF_8).let {if(key.isBlank())it else it.replace(key,"<redacted>")})
                        if(continuation.isActive)continuation.resume(bytes)
                    }}catch(e: Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                }
            })
        }}finally {synchronized(lock){if(active===call)active=null}}
    }
    override fun close(){synchronized(lock){closed=true;active?.cancel()}}
}
