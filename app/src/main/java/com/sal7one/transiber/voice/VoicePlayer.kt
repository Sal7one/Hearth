package com.sal7one.transiber.voice

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sal7one.common_jni.audio.PcmWave
import com.sal7one.common_jni.voice.SupertonicVoice
import com.sal7one.common_jni.voice.VoiceText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID

/** One bounded utterance queue for Android, native and self-hosted speech, with exact cancellation. */
internal class VoicePlayer(context: Context,private val backendOverride: String?=null, private val changed: (Boolean,String?) -> Unit) : AutoCloseable {
    private val context=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Request(val text: String,val language: String,val generation: Long,val requestedBackend: String?=null)
    private val queue=Channel<Request>(3)
    private var generation=0L
    private var operation: Job?=null
    private var systemPending: Pair<String,CompletableDeferred<Unit>>?=null
    private val systemReady=CompletableDeferred<Unit>()
    private var system: TextToSpeech?=null
    @Volatile private var model: SupertonicVoice?=null
    @Volatile private var track: AudioTrack?=null
    private var network: RemoteVoiceClient?=null
    private val audioManager=context.getSystemService(AudioManager::class.java)
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { change -> if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop() }
        .build()
    init {
        scope.launch {
            for(request in queue) {
                if(request.generation!=generation)continue
                operation=launch {
                    var failure: String? = null
                    try {
                        check(audioManager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Android could not grant audio focus for read aloud"}
                        changed(true,null)
                        val saved=VoiceSettings.choice(context)
                        val selectedBackend=request.requestedBackend ?: backendOverride
                        val choice=if(selectedBackend==null)saved else saved.copy(backend=selectedBackend)
                        when(choice.backend) {
                            "system" -> systemSpeak(request.text,request.language,choice.rate)
                            "supertonic" -> local(request,choice)
                            "remote" -> remote(request,choice)
                            else -> error("Unknown voice backend: ${choice.backend}")
                        }
                    }catch(e: CancellationException){throw e}
                    catch(e: Exception){failure=e.message ?: e.toString()}
                    catch(e: LinkageError){failure=e.message ?: e.toString()}
                    finally {audioManager.abandonAudioFocusRequest(focus);if(request.generation==generation)changed(false,failure)}
                }
                operation?.join();operation=null
            }
        }
    }
    fun speak(text: String,language: String,mode: VoicePlaybackMode=VoicePlaybackMode.DEFAULT) {
        scope.launch {stopNow();queueSpeech(text,language,mode)}
    }
    fun enqueue(text: String,language: String,mode: VoicePlaybackMode=VoicePlaybackMode.DEFAULT) {
        scope.launch {queueSpeech(text,language,mode)}
    }
    private fun queueSpeech(text: String,language: String,mode: VoicePlaybackMode) {
        try {
            val override=if(mode==VoicePlaybackMode.DEFAULT)null else VoiceSelection.backend(mode,
                VoiceSettings.choice(context).backend,VoiceSettings.customBackend(context),com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK)
            enqueueNow(text,language,override)
        } catch(e: Exception) {changed(false,e.message ?: e.toString())}
    }
    private fun enqueueNow(text: String,language: String,requestedBackend: String?=null) {
        if(text.isBlank())return
        if(text.length>5000){changed(false,"Read aloud accepts at most 5000 characters");return}
        val owner=audibleOwner
        if(owner!==this){owner?.stop();audibleOwner=this}
        if(!queue.trySend(Request(text,Locale.forLanguageTag(language).toLanguageTag(),generation,requestedBackend)).isSuccess)
            changed(true,"Speech queue is full. Skipped a new line to avoid falling behind.")
    }
    private fun initSystem() {
        if(system!=null)return
        system=TextToSpeech(context){code ->if(code==TextToSpeech.SUCCESS)systemReady.complete(Unit) else systemReady.completeExceptionally(IllegalStateException("Android TTS initialization returned $code"))}.apply {
            setAudioAttributes(audioAttributes)
            setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id: String?)=Unit
                override fun onDone(id: String?)=finish(id,null)
                @Deprecated("Android callback") override fun onError(id: String?)=finish(id,"Android speech failed")
                override fun onError(id: String?,code: Int)=finish(id,"Android speech error: $code")
                override fun onStop(id: String?,interrupted: Boolean)=finish(id,"Android speech was interrupted")
            })
        }
    }
    private fun finish(id: String?,error: String?) {Handler(Looper.getMainLooper()).post {systemPending?.takeIf {it.first==id}?.second?.let {if(error==null)it.complete(Unit) else it.completeExceptionally(IllegalStateException(error))}}}
    private suspend fun systemSpeak(text: String,language: String,rate: Float) {
        initSystem();withTimeout(15000){systemReady.await()};val tts=checkNotNull(system)
        val baseLanguage=Locale.forLanguageTag(language).language
        val candidates=tts.voices.orEmpty().filterNot {it.isNetworkConnectionRequired}
        val saved=VoiceSettings.prefs(context).getString("system-voice-$baseLanguage",null)
        val id=if(saved!=null) candidates.firstOrNull {it.name==saved && it.locale.language==baseLanguage}?.name
            ?: error("Selected Android $language voice is unavailable. Choose a voice in Voice settings")
        else OfflineVoicePolicy.select(candidates.map {OfflineVoicePolicy.Candidate(it.name,it.locale,false,it.quality)},Locale.forLanguageTag(language))
        val voice=candidates.firstOrNull {it.name==id} ?: error("No installed offline $language voice. Install one in Android text-to-speech settings")
        check(tts.setVoice(voice)==TextToSpeech.SUCCESS){"Android could not select voice ${voice.name}"}
        check(tts.setSpeechRate(rate)==TextToSpeech.SUCCESS){"Android could not set speech speed"}
        for(part in VoiceText.chunks(text,300)) {
            val pending=UUID.randomUUID().toString() to CompletableDeferred<Unit>();systemPending=pending
            try {check(tts.speak(part,TextToSpeech.QUEUE_FLUSH,null,pending.first)==TextToSpeech.SUCCESS){"Android rejected the speech request"};withTimeout(120000){pending.second.await()}}
            finally {systemPending=null}
        }
    }
    private suspend fun local(request: Request,choice: VoiceChoice)=nativeGate.withLock {
        val baseLanguage=Locale.forLanguageTag(request.language).language
        require(baseLanguage in VoiceCatalog.languages){"Supertonic 3 does not support ${request.language}. Choose an installed Android voice or a compatible voice server"}
        val models=VoiceModels(File(context.filesDir,"voice-models"))
        try {
            val job=currentCoroutineContext()
            val data=withContext(Dispatchers.IO){models.verify(choice.voice){job.ensureActive()};models.indexer() to models.style(choice.voice)}
            withContext(Dispatchers.IO+NonCancellable){model=models.open()}
            currentCoroutineContext().ensureActive()
            for(part in VoiceText.chunks(request.text)) {
                val samples=withContext(Dispatchers.Default){checkNotNull(model).synthesize(VoiceText.ids(part,baseLanguage,data.first),data.second,choice.steps,choice.rate)}
                currentCoroutineContext().ensureActive();play(samples,SupertonicVoice.SAMPLE_RATE)
            }
        }finally {withContext(Dispatchers.IO+NonCancellable){model?.close();model=null}}
    }
    private suspend fun remote(request: Request,choice: VoiceChoice) {
        val connection=VoiceSettings.remote(context)
        val language=connection.capabilities.languages.firstOrNull {it.equals(request.language,ignoreCase=true)}
            ?: Locale.forLanguageTag(request.language).language.takeIf {it in connection.capabilities.languages}
            ?: error("The selected voice server does not advertise ${request.language}")
        val voice=VoiceSettings.prefs(context).getString("remote-voice",null)?.takeIf {it in connection.capabilities.voices} ?: connection.capabilities.voices.first()
        val client=RemoteVoiceClient();network=client
        try {
            for(part in VoiceText.chunks(request.text,300)) {
                val raw=client.execute(RemoteVoiceProtocol.request(connection.endpoint,connection.key,part,language,voice,connection.capabilities),connection.key,true)
                val pcm=withContext(Dispatchers.Default){PcmWave.decode(raw)}
                play(FloatArray(pcm.samples.size){pcm.samples[it]/32768f},pcm.sampleRate,choice.rate)
            }
        }finally {client.close();network=null}
    }
    private suspend fun play(samples: FloatArray,rate: Int,speed: Float=1f)=withContext(Dispatchers.IO) {
        require(samples.isNotEmpty() && samples.all(Float::isFinite)){"Voice returned empty or invalid audio"}
        val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum>0){"Android cannot play $rate Hz voice audio: $minimum"}
        val player=AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,rate/10*4)).build()
        try {
            check(player.state==AudioTrack.STATE_INITIALIZED){"Voice audio output failed to initialize"}
            track=player;if(speed!=1f)player.playbackParams=PlaybackParams().allowDefaults().setSpeed(speed)
            player.play();var offset=0
            while(offset<samples.size){currentCoroutineContext().ensureActive();val count=player.write(samples,offset,minOf(4096,samples.size-offset),AudioTrack.WRITE_BLOCKING);check(count>0){"Android voice audio write failed: $count"};offset+=count}
            withTimeout(120000){while((player.playbackHeadPosition.toLong() and 0xffffffffL)<samples.size){delay(20)}}
        }finally {track=null;player.release()}
    }
    fun stop(){scope.launch {stopNow()}}
    private fun stopNow() {
        generation++;while(queue.tryReceive().isSuccess)Unit
        model?.cancel();network?.close();operation?.cancel();systemPending?.second?.cancel();system?.stop()
        runCatching {track?.pause();track?.flush()};changed(false,null)
    }
    override fun close(){scope.launch {stopNow();queue.close();scope.cancel();system?.shutdown();system=null;if(audibleOwner===this@VoicePlayer)audibleOwner=null}}
    companion object {
        private val nativeGate=Mutex()
        private var audibleOwner: VoicePlayer?=null
        private val audioAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}
