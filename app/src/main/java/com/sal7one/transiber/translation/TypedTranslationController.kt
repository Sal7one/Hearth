package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TypedTranslationState(val output: String="",val busy: Boolean=false,val error: String?=null)
/** One resident translator, one latest pending request, and immediate stale-result invalidation. */
internal class TypedTranslationController(
    private val open: (ConversationTranslatorSnapshot) -> CancellableTextTranslator,
    private val acquire: () -> AutoCloseable,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    constructor(context: Context) : this({it.open(context.applicationContext)}, {LocalWorkGate.acquire("Typed translation")})
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private data class Request(val text: String,val source: String,val target: String,val route: ConversationTranslatorSnapshot?,val revision: Long)
    private val queue=Channel<Request>(Channel.CONFLATED)
    private val mutable=MutableStateFlow(TypedTranslationState())
    val state=mutable.asStateFlow()
    private var revision=0L
    private var debounce: Job?=null
    @Volatile private var translator: CancellableTextTranslator?=null
    private var route: ConversationTranslatorSnapshot?=null
    private var lease: AutoCloseable?=null
    private var reset=false
    init {scope.launch {
        try {
            for(request in queue) {
                if(request.revision!=revision)continue
                try {
                    if(reset){release();reset=false}
                    if(request.route==null){release();continue}
                    val requestedRoute=request.route
                    if(request.source==request.target){release();mutable.value=TypedTranslationState(request.text);continue}
                    if(route!=requestedRoute) {
                        release()
                        if(requestedRoute.cloud==null)lease=acquire()
                        withContext(io+NonCancellable){translator=open(requestedRoute)}
                        ensureActive();route=requestedRoute
                        if(request.revision!=revision)continue
                    }
                    val engine=checkNotNull(translator);val direction=TranslationDirection(request.source,request.target)
                    check(direction in engine.directions){"${requestedRoute.label} does not support ${request.source} → ${request.target}"}
                    val translated=withContext(io){engine.translate(request.text,direction)}
                    if(request.revision==revision)mutable.value=TypedTranslationState(translated)
                }catch(e: CancellationException){if(!scope.isActive)throw e;release()}
                catch(e: Exception){if(request.revision==revision)mutable.value=TypedTranslationState(error=e.message ?: e.toString());release()}
                catch(e: LinkageError){if(request.revision==revision)mutable.value=TypedTranslationState(error=e.message ?: e.toString());release()}
            }
        }finally {release()}
    }}
    fun update(text: String,source: String,target: String,snapshot: ConversationTranslatorSnapshot?,immediate: Boolean=false) {
        revision++;debounce?.cancel();val current=revision
        mutable.value=TypedTranslationState(busy=text.isNotBlank() && snapshot!=null)
        if(text.isBlank() || snapshot==null){pause();return}
        if(text.length>3000){error(IllegalArgumentException("Type at most 3000 characters"));return}
        debounce=scope.launch {if(!immediate)delay(500);queue.trySend(Request(text,source,target,snapshot,current))}
    }
    fun error(error: Throwable){pause();mutable.value=TypedTranslationState(error=error.message ?: error.toString())}
    fun clear(){pause()}
    fun pause(){
        revision++;debounce?.cancel();reset=true;translator?.cancel()
        mutable.value=TypedTranslationState()
        queue.trySend(Request("","","",null,revision))
    }
    private suspend fun release()=withContext(io+NonCancellable) {
        try {translator?.close()}finally {translator=null;route=null;lease?.close();lease=null}
    }
    override fun close(){debounce?.cancel();translator?.cancel();queue.cancel();scope.cancel()}
}
