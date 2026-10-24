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
internal class CameraOcrController(
    private val openModel: (OcrProfile) -> OcrEngine,
    private val openTranslator: (ConversationTranslatorSnapshot) -> CancellableTextTranslator,
    private val acquire: () -> AutoCloseable,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val recognition: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    constructor(context: Context) : this(
        { OcrModels(File(context.applicationContext.filesDir,"ocr-models")).open(context.applicationContext,it) },
        { it.open(context.applicationContext) },
        { LocalWorkGate.acquire("Camera OCR") },
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(CameraOcrState())
    val state = mutable.asStateFlow()
    private data class Frame(val bitmap: Bitmap, val captured: Boolean, val epoch: Long, val excludedAreas: List<PixelCrop> = emptyList())
    private data class TextRequest(val text: String, val ticket: OcrTranslationWork.Ticket, val lines: List<OcrLine>, val preservesRegions: Boolean = false)
    @Volatile private var frames: Channel<Frame>? = null
    @Volatile private var model: OcrEngine? = null
    @Volatile private var translator: CancellableTextTranslator? = null
    private var job: Job? = null
    @Volatile private var revision = 0L
    private val frameEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val translationWork=OcrTranslationWork()
    fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: error.toString()) } }
    fun start(profile: OcrProfile, source: String, target: String, snapshot: ConversationTranslatorSnapshot?, live: Boolean, initial: Bitmap? = null, positioned: Boolean = false,
              excludedAreas: List<PixelCrop> = emptyList()) {
        if (job != null) { initial?.recycle(); error(IllegalStateException("Stop the current camera session before changing its models or settings")); return }
        val input = Channel<Frame>(1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST, onUndeliveredElement = { it.bitmap.recycle() })
        frameEpoch.incrementAndGet();revision++;translationWork.invalidate()
        frames = input; mutable.value = CameraOcrState(running=true,loading=true,processing=initial!=null,live=live)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: AutoCloseable? = null
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
                    mutable.update {it.copy(translating=true,error=null)}
                    if(!textQueue.trySend(request).isSuccess) {
                        translationWork.finish(request.ticket,{revision}) {
                            mutable.update {it.copy(translating=false,error="OCR translation worker stopped before accepting this page")}
                        }
                    }
                }
            }
            try {
                lease = acquire()
                withContext(io + NonCancellable) { model = openModel(profile) }
                ensureActive(); mutable.update { it.copy(loading=false) }
                if(snapshot != null && source != target) launch {
                    val cache = OcrTranslationCache()
                    try {
                        for(request in textQueue) {
                            if(!current(request))continue
                            try {
                                check(request.text.length <= 3000) { "Recognized text exceeds the 3,000-character translation limit. Capture a smaller area; original text is preserved." }
                                publish(request) {it.copy(translating=true)}
                                suspend fun translate(text: String): String = cache[text] ?: withContext(io) {
                                    if(translator == null) withContext(NonCancellable) { translator = openTranslator(snapshot) }
                                    ensureActive(); val active = checkNotNull(translator); val direction=TranslationDirection(source,target)
                                    check(direction in active.directions) { "${snapshot.label} does not support $source → $target" }
                                    active.translate(text,direction).also {
                                        check(it.isNotBlank()) { "Translation returned empty text" }
                                    }
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
                            } catch(e: CancellationException) {
                                // A provider may cancel just this call (for example its
                                // own timeout). Only session cancellation retires the
                                // worker; otherwise later Translate taps must still work.
                                currentCoroutineContext().ensureActive()
                                publish(request) {it.copy(error=e.message ?: "Translation was cancelled")}
                            }
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
                        fun included(line: OcrLine) = !OcrExcludedAreas.containsText(line.x,line.y,line.width,line.height,frame.excludedAreas)
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
                                val includedRegions=regions.filterNot { region -> region.bounds.let { OcrExcludedAreas.containsText(it.x,it.y,it.width,it.height,frame.excludedAreas) } }
                                mutable.update { if(frame.epoch!=frameEpoch.get())it else it.copy(regions=includedRegions,detecting=false,width=bitmap.width,height=bitmap.height) }
                            }
                            override fun stage(stage: PageOcrStage) {
                                if(isCurrent())mutable.update {if(frame.epoch!=frameEpoch.get() || progressive && pageRevision!=revision)it else it.copy(stage=stage)}
                            }
                            override fun recognized(text: List<PageText>) {
                                if(!progressive || !isCurrent())return
                                val recognized=text.map { it.toOcrLine() }.filter(::included)
                                val original=recognized.joinToString("\n") { it.text }
                                mutable.update {
                                    if(frame.epoch!=frameEpoch.get())it else {
                                        val refreshed=OcrPageTranslation.refreshGeometry(it.boxTranslations,recognized)
                                        it.copy(lines=recognized,text=original,boxTranslations=refreshed,
                                            translation=refreshed.joinToString("\n") {box->box.translation})
                                    }
                                }
                                if(positioned && original.isNotBlank() && snapshot!=null && source!=target && isCurrent())
                                    request(original,pageRevision,recognized,true)?.let(::enqueue)
                            }
                        }
                        val lines=OcrFrameWork.run(frame.epoch,frameEpoch::get) {
                            withContext(recognition) {
                                val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                                engine.recognizePage(pixels,bitmap.width,bitmap.height,if(frame.captured)960 else 640,progress)
                                    .filter(::included).map { it.copy(text=OcrText.logical(it.text,profile.id == "arabic")) }
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
                        mutable.update {it.copy(processing=false,detecting=false,
                            // An unchanged live frame does not enqueue another request,
                            // but the preceding frame's translator can still be busy.
                            translating=finalRequest!=null || translationWork.hasCurrent {revision},
                            lines=lines,width=bitmap.width,height=bitmap.height,ocrMs=(System.nanoTime()-begun)/1_000_000)}
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
                    withContext(io) {
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
        initial?.let { input.trySend(Frame(it,true,frameEpoch.get(),excludedAreas.toList())) }
        job?.start()
    }
    /** Takes ownership only when a running worker can consume this frame. */
    fun offer(bitmap: Bitmap, captured: Boolean = false, excludedAreas: List<PixelCrop> = emptyList()) {
        val queue=frames
        if(queue == null || (!captured && !mutable.value.live)) { bitmap.recycle();return }
        if(captured) {
            frameEpoch.incrementAndGet(); revision++
            translationWork.invalidate()
            mutable.update { it.copy(live=false,processing=true,detecting=false,text="",translation="",boxTranslations=emptyList(),lines=emptyList(),regions=emptyList(),error=null) }
        }
        if(!queue.trySend(Frame(bitmap,captured,frameEpoch.get(),excludedAreas.toList())).isSuccess)bitmap.recycle()
    }
    /** Invalidate work immediately when the reader moves, without destroying the loaded model. */
    fun invalidate() {
        frameEpoch.incrementAndGet();revision++;translationWork.invalidate()
        mutable.update { it.copy(text="",translation="",boxTranslations=emptyList(),lines=emptyList(),regions=emptyList(),processing=false,detecting=false,error=null,translating=false) }
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
