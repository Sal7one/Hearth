package com.sal7one.transiber.ocr

import android.content.Context
import android.graphics.Bitmap
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.common_jni.ocr.toOcrLine
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.PageOcrProgress
import com.sal7one.hearth.core.vision.PageText
import com.sal7one.hearth.core.vision.PageOcrStage
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.ConversationTranslatorSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class CameraOcrState(
    val running: Boolean = false, val loading: Boolean = false, val live: Boolean = false,
    val processing: Boolean = false, val closing: Boolean = false, val text: String = "", val translation: String = "",
    val lines: List<OcrLine> = emptyList(), val width: Int = 1, val height: Int = 1,
    val regions: List<OcrRegion> = emptyList(), val detecting: Boolean = false,
    val stage: PageOcrStage? = null,
    val boxTranslations: List<TranslatedOcrBox> = emptyList(),
    val ocrMs: Long = 0, val translating: Boolean = false, val error: String? = null,
)
/** Bounded OCR + translation workers; stale translations cannot replace a newer camera view. */
internal class CameraOcrController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CameraOcrState())
    val state = mutable.asStateFlow()
    private data class Frame(val bitmap: Bitmap, val captured: Boolean, val epoch: Long)
    private data class TextRequest(val text: String, val ticket: OcrTranslationWork.Ticket, val lines: List<OcrLine>, val preservesRegions: Boolean = false)
    @Volatile private var frames: Channel<Frame>? = null
    @Volatile private var model: OcrEngine? = null
    @Volatile private var translator: CancellableTextTranslator? = null
    private var job: Job? = null
    @Volatile private var revision = 0L
    private val frameEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val translationWork=OcrTranslationWork()
    fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: error.toString()) } }
    fun start(profile: OcrProfile, source: String, target: String, snapshot: ConversationTranslatorSnapshot?, live: Boolean, initial: Bitmap? = null, positioned: Boolean = false) {
        if (job != null) { initial?.recycle(); error(IllegalStateException("Stop the current camera session before changing its models or settings")); return }
        val input = Channel<Frame>(1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST, onUndeliveredElement = { it.bitmap.recycle() })
        frameEpoch.incrementAndGet();revision++;translationWork.invalidate()
        frames = input; mutable.value = CameraOcrState(running=true,loading=true,processing=initial!=null,live=live)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: LocalWorkGate.Lease? = null
            val textQueue = Channel<TextRequest>(Channel.CONFLATED)
            fun current(request: TextRequest)=translationWork.isCurrent(request.ticket){revision}
            fun publish(request: TextRequest,change: (CameraOcrState)->CameraOcrState) {
                translationWork.publish(request.ticket,{revision}) {mutable.update(change)}
            }
            fun request(text: String,pageRevision: Long,lines: List<OcrLine>,preservesRegions: Boolean): TextRequest? =
                translationWork.begin(pageRevision){revision}?.let {TextRequest(text,it,lines,preservesRegions)}
            fun enqueue(request: TextRequest) {
                // A late producer must not replace newer work in the conflated queue.
                translationWork.publish(request.ticket,{revision}) {
                    mutable.update {it.copy(translating=true)}
                    if(!textQueue.trySend(request).isSuccess) {
                        translationWork.finish(request.ticket,{revision}) {
                            mutable.update {it.copy(translating=false,error="OCR translation worker stopped before accepting this page")}
                        }
                    }
                }
            }
            try {
                lease = LocalWorkGate.acquire("Camera OCR")
                withContext(Dispatchers.IO + NonCancellable) { model = OcrModels(File(context.filesDir,"ocr-models")).open(context,profile) }
                ensureActive(); mutable.update { it.copy(loading=false) }
                if(snapshot != null && source != target) launch {
                    val cache = OcrTranslationCache()
                    try {
                        for(request in textQueue) {
                            if(!current(request))continue
                            try {
                                check(request.text.length <= 3000) { "Recognized text exceeds the 3,000-character translation limit. Capture a smaller area; original text is preserved." }
                                publish(request) {it.copy(translating=true)}
                                suspend fun translate(text: String): String = cache[text] ?: withContext(Dispatchers.IO) {
                                    if(translator == null) withContext(NonCancellable) { translator = snapshot.open(context) }
                                    ensureActive(); val active = checkNotNull(translator); val direction=TranslationDirection(source,target)
                                    check(direction in active.directions) { "${snapshot.label} does not support $source → $target" }
                                    active.translate(text,direction)
                                }.also { cache.put(text,it) }
                                if(positioned) {
                                    OcrPageTranslation.run(request.lines, {current(request)}, ::translate, { boxes ->
                                        publish(request) {
                                            val refreshed=OcrPageTranslation.refreshGeometry(boxes,it.lines,request.preservesRegions)
                                            it.copy(boxTranslations=refreshed,translation=refreshed.joinToString("\n") {box->box.translation},error=null)
                                        }
                                    }, cached = { cache[it] })
                                } else {
                                    val output=translate(request.text)
                                    publish(request) {it.copy(translation=output,error=null)}
                                }
                            } catch(e: CancellationException) { throw e }
                            catch(e: Exception) {publish(request) {it.copy(error=e.message ?: e.toString())}}
                            catch(e: LinkageError) {publish(request) {it.copy(error=e.message ?: e.toString())}}
                            finally {
                                translationWork.finish(request.ticket,{revision}) {mutable.update {it.copy(translating=false)}}
                            }
                        }
                    } finally { textQueue.cancel() }
                }
                val stability=OcrStability()
                for(frame in input) {
                    try {
                        if(frame.epoch != frameEpoch.get()) continue
                        val bitmap=frame.bitmap
                        val begun=System.nanoTime()
                        val engine=checkNotNull(model)
                        val progressive=engine.preservesRegions
                        val pageRevision=if(progressive) ++revision else revision
                        if(progressive) {
                            translationWork.invalidate()
                            mutable.update {it.copy(detecting=true,stage=null,regions=emptyList(),lines=emptyList(),text="",translation="",boxTranslations=emptyList(),translating=false)}
                        }
                        val work=currentCoroutineContext()
                        val progress=object : PageOcrProgress {
                            override fun isCurrent(): Boolean {
                                work.ensureActive()
                                return frame.epoch==frameEpoch.get() && (!progressive || pageRevision==revision)
                            }
                            override fun detected(regions: List<OcrRegion>) {
                                if(!isCurrent())return
                                mutable.update { if(frame.epoch!=frameEpoch.get())it else it.copy(regions=regions,detecting=false,width=bitmap.width,height=bitmap.height) }
                            }
                            override fun stage(stage: PageOcrStage) {
                                if(isCurrent())mutable.update {if(frame.epoch!=frameEpoch.get() || progressive && pageRevision!=revision)it else it.copy(stage=stage)}
                            }
                            override fun recognized(text: List<PageText>) {
                                if(!progressive || !isCurrent())return
                                val recognized=text.map { it.toOcrLine() }
                                val original=recognized.joinToString("\n") { it.text }
                                mutable.update { if(frame.epoch!=frameEpoch.get())it else it.copy(lines=recognized,text=original,
                                    boxTranslations=OcrPageTranslation.refreshGeometry(it.boxTranslations,recognized)) }
                                if(positioned && original.isNotBlank() && snapshot!=null && source!=target && isCurrent())
                                    request(original,pageRevision,recognized,true)?.let(::enqueue)
                            }
                        }
                        val lines=OcrFrameWork.run(frame.epoch,frameEpoch::get) {
                            withContext(Dispatchers.Default) {
                                val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                                engine.recognizePage(pixels,bitmap.width,bitmap.height,if(frame.captured)960 else 640,progress)
                                    .map { it.copy(text=OcrText.logical(it.text,profile.id == "arabic")) }
                            }
                        } ?: continue // The old native call can finish or fail after the page has moved.
                        val readingLines=if(positioned && !engine.preservesRegions) OcrReadingGroups.group(lines) else lines
                        val text=lines.joinToString("\n") { it.text }
                        val settled = stability.observe(text,frame.captured)
                        if(text.isBlank() && !frame.captured && !settled) continue
                        if(!progressive && (positioned || text != mutable.value.text)) {
                            revision++;translationWork.invalidate();mutable.update {it.copy(text=text,translation="",boxTranslations=emptyList())}
                        }
                        // Register final work before publishing processing=false. An older
                        // progressive request's finally must not make this page terminal.
                        val finalRequest=if(settled && text.isNotBlank() && snapshot!=null && source!=target)
                            request(text,revision,readingLines,progressive) else null
                        mutable.update {it.copy(processing=false,detecting=false,translating=finalRequest!=null,lines=lines,width=bitmap.width,height=bitmap.height,ocrMs=(System.nanoTime()-begun)/1_000_000)}
                        finalRequest?.let(::enqueue)
                    } finally { frame.bitmap.recycle() }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e) }
            catch(e: LinkageError) { error(e) }
            finally {
                translationWork.invalidate()
                textQueue.cancel(); input.cancel(); frames=null
                // Children finish cancellation before shared runtime destruction.
                val children = coroutineContext[Job]?.children?.toList().orEmpty()
                children.forEach { it.cancel() }
                withContext(NonCancellable) {
                    children.joinAll()
                    withContext(Dispatchers.IO) {
                        try { model?.close() }
                        finally {
                            model=null
                            try { translator?.close() }
                            finally { translator=null;lease?.close() }
                        }
                    }
                }
                mutable.update { it.copy(running=false,loading=false,processing=false,detecting=false,closing=false,live=false,translating=false) }; job=null
            }
        }
        initial?.let { input.trySend(Frame(it,true,frameEpoch.get())) }
        job?.start()
    }
    /** Takes ownership only when a running worker can consume this frame. */
    fun offer(bitmap: Bitmap, captured: Boolean = false) {
        val queue=frames
        if(queue == null || (!captured && !mutable.value.live)) { bitmap.recycle();return }
        if(captured) {
            frameEpoch.incrementAndGet(); revision++
            translationWork.invalidate()
            mutable.update { it.copy(live=false,processing=true,detecting=false,text="",translation="",boxTranslations=emptyList(),lines=emptyList(),regions=emptyList(),error=null) }
        }
        if(!queue.trySend(Frame(bitmap,captured,frameEpoch.get())).isSuccess)bitmap.recycle()
    }
    /** Invalidate work immediately when the reader moves, without destroying the loaded model. */
    fun invalidate() {
        frameEpoch.incrementAndGet();revision++;translationWork.invalidate()
        mutable.update { it.copy(text="",translation="",boxTranslations=emptyList(),lines=emptyList(),regions=emptyList(),detecting=false,error=null,translating=false) }
    }
    suspend fun awaitStopped() { job?.join() }
    fun freeze() { mutable.update { it.copy(live=false) } }
    fun stop() {
        if(job == null)return
        revision++;translationWork.invalidate()
        mutable.update { it.copy(live=false,closing=true) }; model?.cancel();translator?.cancel();job?.cancel()
    }
    override fun close() { stop();scope.cancel() }
}
