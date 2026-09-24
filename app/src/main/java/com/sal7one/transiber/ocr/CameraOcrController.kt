package com.sal7one.transiber.ocr

import android.content.Context
import android.graphics.Bitmap
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.common_jni.ocr.OcrEngine
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
    private data class TextRequest(val text: String, val revision: Long, val lines: List<OcrLine>)
    @Volatile private var frames: Channel<Frame>? = null
    @Volatile private var model: OcrEngine? = null
    @Volatile private var translator: CancellableTextTranslator? = null
    private var job: Job? = null
    private var revision = 0L
    private val frameEpoch = java.util.concurrent.atomic.AtomicLong(0)
    fun error(error: Throwable) { mutable.update { it.copy(error = error.message ?: error.toString()) } }
    fun start(profile: OcrProfile, source: String, target: String, snapshot: ConversationTranslatorSnapshot?, live: Boolean, initial: Bitmap? = null, positioned: Boolean = false) {
        if (job != null) { initial?.recycle(); error(IllegalStateException("Stop the current camera session before changing its models or settings")); return }
        val input = Channel<Frame>(1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST, onUndeliveredElement = { it.bitmap.recycle() })
        frameEpoch.incrementAndGet(); frames = input; mutable.value = CameraOcrState(running=true,loading=true,processing=initial!=null,live=live); revision++
        job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: LocalWorkGate.Lease? = null
            val textQueue = Channel<TextRequest>(Channel.CONFLATED)
            try {
                lease = LocalWorkGate.acquire("Camera OCR")
                withContext(Dispatchers.IO + NonCancellable) { model = OcrModels(File(context.filesDir,"ocr-models")).open(context,profile) }
                ensureActive(); mutable.update { it.copy(loading=false) }
                if(snapshot != null && source != target) launch {
                    val cache = OcrTranslationCache()
                    try {
                        for(request in textQueue) {
                            if(request.revision != revision) continue
                            try {
                                check(request.text.length <= 3000) { "Recognized text exceeds the 3,000-character translation limit. Capture a smaller area; original text is preserved." }
                                mutable.update { it.copy(translating=true) }
                                suspend fun translate(text: String): String = cache[text] ?: withContext(Dispatchers.IO) {
                                    if(translator == null) withContext(NonCancellable) { translator = snapshot.open(context) }
                                    ensureActive(); val active = checkNotNull(translator); val direction=TranslationDirection(source,target)
                                    check(direction in active.directions) { "${snapshot.label} does not support $source → $target" }
                                    active.translate(text,direction)
                                }.also { cache.put(text,it) }
                                if(positioned) {
                                    OcrPageTranslation.run(request.lines, {request.revision == revision}, ::translate, { boxes ->
                                        mutable.update { it.copy(boxTranslations=boxes,translation=boxes.joinToString("\n") {box->box.translation},error=null) }
                                    }, cached = { cache[it] })
                                } else {
                                    val output=translate(request.text)
                                    if(request.revision == revision) mutable.update { it.copy(translation=output,error=null) }
                                }
                            } catch(e: CancellationException) { throw e }
                            catch(e: Exception) { if(request.revision == revision) error(e) }
                            catch(e: LinkageError) { if(request.revision == revision) error(e) }
                            finally { if(request.revision == revision) mutable.update { it.copy(translating=false) } }
                        }
                    } finally { textQueue.cancel() }
                }
                val stability=OcrStability()
                for(frame in input) {
                    try {
                        if(frame.epoch != frameEpoch.get()) continue
                        val bitmap=frame.bitmap
                        val begun=System.nanoTime()
                        val lines=withContext(Dispatchers.Default) {
                            val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                            checkNotNull(model).recognize(pixels,bitmap.width,bitmap.height,if(frame.captured)960 else 640)
                                .map { it.copy(text=OcrText.logical(it.text,profile.id == "arabic")) }
                        }
                        ensureActive()
                        if(frame.epoch != frameEpoch.get()) continue // A stale frame must not mark the newer request finished.
                        val readingLines=if(positioned) OcrReadingGroups.group(lines) else lines
                        val text=lines.joinToString("\n") { it.text }
                        val settled = stability.observe(text,frame.captured)
                        if(text.isBlank() && !frame.captured && !settled) continue
                        if(positioned || text != mutable.value.text) { revision++;mutable.update { it.copy(text=text,translation="",boxTranslations=emptyList()) } }
                        mutable.update { it.copy(processing=false,translating=settled && text.isNotBlank() && snapshot!=null && source!=target,lines=lines,width=bitmap.width,height=bitmap.height,ocrMs=(System.nanoTime()-begun)/1_000_000) }
                        if(settled && text.isNotBlank() && snapshot != null && source != target) textQueue.trySend(TextRequest(text,revision,readingLines))
                    } finally { frame.bitmap.recycle() }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e) }
            catch(e: LinkageError) { error(e) }
            finally {
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
                mutable.update { it.copy(running=false,loading=false,processing=false,closing=false,live=false,translating=false) }; job=null
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
            mutable.update { it.copy(live=false,processing=true,text="",translation="",boxTranslations=emptyList(),lines=emptyList(),error=null) }
        }
        if(!queue.trySend(Frame(bitmap,captured,frameEpoch.get())).isSuccess)bitmap.recycle()
    }
    /** Invalidate work immediately when the reader moves, without destroying the loaded model. */
    fun invalidate() {
        frameEpoch.incrementAndGet();revision++
        mutable.update { it.copy(text="",translation="",boxTranslations=emptyList(),lines=emptyList(),error=null,translating=false) }
    }
    suspend fun awaitStopped() { job?.join() }
    fun freeze() { mutable.update { it.copy(live=false) } }
    fun stop() {
        if(job == null)return
        mutable.update { it.copy(live=false,closing=true) }; model?.cancel();translator?.cancel();job?.cancel()
    }
    override fun close() { stop();scope.cancel() }
}
