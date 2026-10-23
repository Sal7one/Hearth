package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.core.vision.*
import com.sal7one.hearth.desktop.runtime.DesktopMangaOcr
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.ceil

internal data class DesktopReadingState(
    val image: BufferedImage?=null,val name: String="",val crop: OcrRect?=null,
    val busy: Boolean=false,val phase: String="Open a manga page to begin",
    val regions: List<OcrRegion> = emptyList(),val text: List<PageText> = emptyList(),
    val translations: Map<OcrRect,String> = emptyMap(),val error: String?=null,
)

/** Shared by UI and loopback image sessions: cancellation joins native work before admission passes. */
internal object DesktopReadingAdmission { val mutex=Mutex() }

/** Owns image OCR separately, then submits bounded text jobs to the existing serialized translation pipeline. */
internal class DesktopReadingController(
    private val pipeline: PipelineService,
    private val config: StateFlow<PipelineConfig>,
    parent: CoroutineScope,
    private val openReader: ((Path)->PageOcrReader)? = null,
) {
    private val owner=SupervisorJob(parent.coroutineContext[Job])
    private val scope=CoroutineScope(parent.coroutineContext+owner)
    private val epoch=AtomicLong()
    private val mutable=MutableStateFlow(DesktopReadingState())
    val state=mutable.asStateFlow()
    private var operation: Job?=null
    @Volatile private var reader: PageOcrReader?=null
    private var readerPath: Path?=null
    private var readerProfile: String?=null
    private data class TranslationKey(val config: PipelineConfig, val text: String)
    private val translationCache = object : LinkedHashMap<TranslationKey, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationKey, String>?) = size > 256
    }

    fun openImage(path: Path) = replace { token ->
        val image=withContext(Dispatchers.IO) { decodeReadingImage(path) }
        if(epoch.get()==token)mutable.value=DesktopReadingState(image=image,name=path.fileName.toString(),phase="Ready · ${image.width} × ${image.height}")
    }
    fun selectArea(area: OcrRect?) {
        if(state.value.busy)return
        val image=state.value.image ?: return
        require(area==null || area.x>=0 && area.y>=0 && area.width>=8 && area.height>=8 && area.right<=image.width && area.bottom<=image.height)
        mutable.update {it.copy(crop=area,regions=emptyList(),text=emptyList(),translations=emptyMap(),error=null)}
    }
    fun read(directory: Path, translate: Boolean,profileId: String?=null): Job? {
        val image=state.value.image ?: return null
        return readImage(image, state.value.crop ?: OcrRect(0,0,image.width,image.height), directory, translate, profileId)
    }
    /** Screen frames use the same resident reader and translator as imported pages. No frame queue is retained. */
    fun readFrame(image: BufferedImage, directory: Path, translate: Boolean, profileId: String?=null): Job? {
        require(image.width in 1..2048 && image.height in 1..2048) { "Screen frame exceeds the 2048-pixel OCR bound" }
        return readImage(image, OcrRect(0,0,image.width,image.height), directory, translate, profileId, "Selected screen region")
    }
    private fun readImage(image: BufferedImage, area: OcrRect, directory: Path, translate: Boolean, profileId: String?, name: String?=null): Job? {
        val settings=config.value.copy(sourceLanguage="ja",speech=null,voice=null)
        return replace { token ->
            if(name != null) mutable.value=DesktopReadingState(image=image,name=name,busy=true)
            mutable.update {it.copy(busy=true,phase="Loading manga models…",regions=emptyList(),text=emptyList(),translations=emptyMap(),error=null)}
            if(readerPath!=directory || readerProfile!=profileId || reader==null) {
                releaseReader()
                withContext(Dispatchers.IO+NonCancellable) {
                    reader=openReader?.invoke(directory) ?: DesktopMangaOcr(directory,profileId)
                    readerPath=directory;readerProfile=profileId
                }
            }
            currentCoroutineContext().ensureActive()
            val active=checkNotNull(reader)
            val pixels=withContext(Dispatchers.Default) {image.getRGB(area.x,area.y,area.width,area.height,null,0,area.width)}
            mutable.update {it.copy(phase="Finding text and speech bubbles…")}
            coroutineScope {
                val pending=Channel<PageText>(64)
                val queued=HashSet<Pair<OcrRect,String>>()
                val work=currentCoroutineContext()
                val translation=launch {
                    if(!translate)return@launch
                    check(settings.translation!=null) {"Choose a translation engine in Settings first"}
                    for(item in pending) {
                        val key=TranslationKey(settings,item.text)
                        val cached=translationCache[key]
                        if(cached!=null) {
                            if(epoch.get()==token) mutable.update {it.copy(translations=it.translations+(item.region.bounds to cached))}
                            continue
                        }
                        val session=pipeline.start(PipelineRequest(PipelineInput.Text(item.text),settings))
                        try {
                            val result=session.state.first {it.terminal}
                            check(result.status==PipelineStatus.COMPLETED) {result.error ?: "Page translation ${result.status.name.lowercase()}"}
                            val text=result.translatedText.trim()
                            check(text.isNotBlank()) {"Translation engine returned empty text for: ${item.text}"}
                            translationCache[key]=text
                            if(epoch.get()==token)mutable.update {it.copy(translations=it.translations+(item.region.bounds to text))}
                        } finally {withContext(NonCancellable) {session.cancel()}}
                    }
                }
                try {
                    withContext(Dispatchers.Default) {
                        active.recognize(pixels,area.width,area.height,object:PageOcrProgress {
                            override fun isCurrent():Boolean {work.ensureActive();return epoch.get()==token}
                            private fun OcrRegion.page()=translated(area.x,area.y,image.width,image.height)
                            override fun stage(stage: PageOcrStage) {
                                if(isCurrent())mutable.update {it.copy(phase=when(stage){
                                    PageOcrStage.PREVIEW->"Finding a quick outline…"
                                    PageOcrStage.SEGMENTATION->"Refining outlines with Koharu masks and panel layout… This CPU quality pass can take a minute."
                                    PageOcrStage.RECOGNITION->"Reading Japanese text inside each region…"
                                    PageOcrStage.CLEANUP->"Removing source text while preserving the page…"
                                })}
                            }
                            override fun detected(regions: List<OcrRegion>) {
                                if(isCurrent())mutable.update {it.copy(regions=regions.map {r->r.page()},phase="Reading ${regions.size} text regions…")}
                            }
                            override fun recognized(text: List<PageText>) {
                                if(!isCurrent())return
                                val page=text.map {it.copy(region=it.region.page())}
                                check(page.sumOf {it.text.length.toLong()}<=3000) {"Page exceeds 3,000 characters. Draw a smaller area; recognized regions are preserved."}
                                mutable.update {it.copy(text=page,phase="Read ${page.size} of ${it.regions.size} regions")}
                                // Cleanup republishes the complete list with new paints. Only new
                                // transcripts need translation; every paint still reaches the UI.
                                if(translate) page.forEach {item ->
                                    if(queued.add(item.region.bounds to item.text))
                                        check(pending.trySend(item).isSuccess) {"Page translation queue is full"}
                                }
                            }
                        })
                    }
                    pending.close()
                    if(translate)mutable.update {it.copy(phase="Finishing translations…")}
                    translation.join()
                } finally {pending.cancel()}
            }
            if(epoch.get()==token)mutable.update {it.copy(phase=if(it.text.isEmpty())"No text found. Draw an area or try another page." else "${it.text.size} regions read"+(if(translate)" · ${it.translations.size} translated" else ""))}
        }
    }
    fun cancel() {
        epoch.incrementAndGet();operation?.cancel();reader?.cancel()
        // Native cancellation is terminal for an owned session. Even an idle
        // cancel/reset must force reopening rather than reuse a poisoned reader.
        readerPath=null;readerProfile=null
        mutable.update {it.copy(busy=false,phase="Cancelled · completed regions are preserved")}
    }
    fun resetResults() {
        cancel()
        mutable.update {it.copy(regions=emptyList(),text=emptyList(),translations=emptyMap(),error=null,phase="Ready · choose Read page to use the selected pipeline")}
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun replace(block: suspend (Long)->Unit): Job? {
        if(!owner.isActive)return null
        val token=epoch.incrementAndGet()
        val previous=operation
        if(previous?.isActive==true) {previous.cancel();reader?.cancel()}
        mutable.update {it.copy(busy=true,error=null,phase="Preparing…")}
        return scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                // Rapid replacement must preserve the whole join chain, including
                // a cancelled intermediate request that was still awaiting native cleanup.
                withContext(NonCancellable) {previous?.join()}
                ensureActive()
                DesktopReadingAdmission.mutex.withLock {
                    try { block(token) }
                    finally { if(!isActive) releaseReader() }
                }
            }
            catch(error: CancellationException) {throw error}
            catch(error: Throwable) {
                if(epoch.get()==token)mutable.update {it.copy(error=error.message ?: error.toString(),phase="Reading failed")}
            } finally {
                if(epoch.get()==token)mutable.update {it.copy(busy=false)}
            }
        }.also {operation=it}
    }
    private suspend fun releaseReader()=withContext(Dispatchers.IO+NonCancellable) {
        val old=reader;reader=null;readerPath=null;readerProfile=null;old?.close()
    }
    suspend fun releaseWhenIdle() {
        operation?.join()
        withContext(NonCancellable) {DesktopReadingAdmission.mutex.withLock {releaseReader()}}
    }
    suspend fun close() {
        epoch.incrementAndGet();reader?.cancel();owner.cancelAndJoin()
        withContext(NonCancellable) {DesktopReadingAdmission.mutex.withLock {releaseReader()}}
    }
}

/** Inspect image dimensions before decoding; subsample oversized pages to the supported native bound. */
internal fun decodeReadingImage(path: Path): BufferedImage {
    require(Files.isRegularFile(path) && Files.size(path) in 1..64L*1024*1024) {"Choose an image smaller than 64 MiB"}
    ImageIO.createImageInputStream(path.toFile()).use { input ->
        checkNotNull(input) {"Cannot open image"}
        val decoders=ImageIO.getImageReaders(input)
        check(decoders.hasNext()) {"Unsupported image. Choose PNG or JPEG."}
        val decoder=decoders.next()
        try {
            decoder.input=input
            val width=decoder.getWidth(0);val height=decoder.getHeight(0)
            require(width>0 && height>0 && width.toLong()*height<=100_000_000) {"Image exceeds the 100-megapixel limit"}
            val step=ceil(maxOf(width,height)/2048.0).toInt().coerceAtLeast(1)
            val params=decoder.defaultReadParam.apply {setSourceSubsampling(step,step,0,0)}
            return decoder.read(0,params) ?: error("Image decoder returned no pixels")
        } finally {decoder.dispose()}
    }
}
