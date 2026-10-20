package com.sal7one.transiber.voice

import android.content.Context
import com.sal7one.hearth.tts.magpie.MagpieCatalog
import com.sal7one.hearth.tts.magpie.MagpieModels
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.runtime.LocalModelPolicy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Public model host only: no provider headers/credentials. Strict ranges prevent a1.47GB accidental download. */
internal object MagpieTokenizerDownload {
    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        check(ByokPolicy.FEATURE_BYOK){"Tokenizer downloads are unavailable in the offline build; import a verified tokenizer range file"}
        MagpiePolicy.requireAllowed(context,"Magpie tokenizer download")
        val root=File(context.filesDir,"voice-models/magpie");root.mkdirs()
        val file=File.createTempFile("tokenizer-ranges-",".part",root)
        val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).build()
        try {
            file.outputStream().use {sink ->
                for(range in MagpieCatalog.ranges)coroutineScope {
                    ensureActive()
                    val url="${MagpieCatalog.source}/resolve/${MagpieCatalog.revision}/magpie_tts_multilingual_357m.nemo"
                    val call=client.newCall(Request.Builder().url(url).header("Range","bytes=${range.first}-${range.last}").header("Accept-Encoding","identity").build())
                    val watcher=launch(Dispatchers.IO){try{awaitCancellation()}finally{call.cancel()}}
                    try {call.execute().use {response ->
                        check(response.code==206){"Model host did not honor the tokenizer range request: HTTP ${response.code}"}
                        check(response.header("Content-Range")=="bytes ${range.first}-${range.last}/1470208000"){"Model host returned the wrong tokenizer range"}
                        val body=checkNotNull(response.body){"Empty tokenizer response"}
                        val expected=range.last-range.first+1
                        check(body.contentLength()==expected){"Tokenizer response length does not match the pinned range"}
                        body.byteStream().use {input ->val buffer=ByteArray(65536);var total=0L;while(true){ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;check(total<=expected){"Tokenizer response exceeds bound"};sink.write(buffer,0,n)};check(total==expected){"Tokenizer download is incomplete"}}
                    }}finally{watcher.cancel()}
                }
            }
            val job=currentCoroutineContext();MagpieModels(root).installTokenizerSegments(file){job.ensureActive()}
        }finally{file.delete();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
}
