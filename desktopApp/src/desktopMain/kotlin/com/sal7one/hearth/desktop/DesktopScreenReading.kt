package com.sal7one.hearth.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Coordinates are AWT logical screen pixels; image pixels may be downsampled on HiDPI displays. */
internal data class DesktopScreenRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(width in 32..8192 && height in 32..8192 && width.toLong()*height<=33_554_432) { "Choose a screen area between 32 pixels and 32 megapixels" } }
    fun rectangle() = Rectangle(x,y,width,height)
}
internal data class DesktopScreenReadingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val region: DesktopScreenRegion? = null,
    val overlayReady: Boolean = false,
    val completedFrames: Int = 0,
    val phase: String = "Choose a screen area to read Japanese pages in another app",
    val error: String? = null,
)

/** One resident reader, one frame in flight, no accumulated screenshot backlog. */
internal class DesktopScreenReadingController(
    private val reading: DesktopReadingController,
    parent: CoroutineScope,
    private val capture: suspend (DesktopScreenRegion) -> BufferedImage,
    private val pollMs: Long = 1_000,
    private val settleMs: Long = 350,
) {
    private val owner=SupervisorJob(parent.coroutineContext[Job])
    private val scope=CoroutineScope(parent.coroutineContext+owner)
    private val mutable=MutableStateFlow(DesktopScreenReadingState())
    val state=mutable.asStateFlow()
    private var work: Job?=null
    private val generation=AtomicLong()
    private val refresh=AtomicLong()
    init {require(pollMs>=10 && settleMs>=0)}

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(region: DesktopScreenRegion, directory: Path, translate: Boolean, profileId: String): Job {
        val token=generation.incrementAndGet()
        val previous=work
        previous?.cancel()
        reading.cancel()
        mutable.value=DesktopScreenReadingState(active=true,region=region,phase="Waiting for the page to settle…")
        return scope.launch(start=CoroutineStart.ATOMIC) {
            try {
                // The cancelled predecessor may still own native handles. Join its cleanup
                // chain before allowing the replacement screen service to submit a frame.
                withContext(NonCancellable) {previous?.join()}
                ensureActive()
                var last: IntArray?=null
                var revision=refresh.get()
                while(isActive) {
                    if(revision!=refresh.get()) {last=null;revision=refresh.get()}
                    if(mutable.value.paused) {last=null;delay(pollMs);continue}
                    val frame=capture(region)
                    val signature=readingFrameSignature(frame)
                    if(last==null || readingFrameChanged(last,signature)) {
                        mutable.update {it.copy(overlayReady=false,phase="Page changed · waiting for a steady frame…")}
                        delay(settleMs)
                        val stable=capture(region)
                        val stableSignature=readingFrameSignature(stable)
                        if(readingFrameChanged(signature,stableSignature)) {delay(pollMs);continue}
                        mutable.update {it.copy(phase="Reading the selected screen area…")}
                        reading.readFrame(stable,directory,translate,profileId)?.join()
                        ensureActive()
                        reading.state.value.error?.let {error(it)}
                        val current=readingFrameSignature(capture(region))
                        // Never paste a completed old page onto a browser that scrolled
                        // while OCR or translation was running.
                        if(!readingFrameChanged(stableSignature,current)) {
                            last=stableSignature
                            mutable.update {it.copy(overlayReady=!it.paused,completedFrames=it.completedFrames+1,phase="Watching for page changes · clicks pass through")}
                        } else mutable.update {it.copy(overlayReady=false,phase="Page moved while reading · refreshing…")}
                    }
                    delay(pollMs)
                }
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(failure: Exception) {
                if(generation.get()==token) mutable.update {it.copy(error=failure.message ?: failure.toString(),phase="Screen reading stopped")}
            } finally {
                // Stop may return immediately to the UI; native ownership still remains
                // serialized by DesktopReadingController's cancellation join chain.
                if(!isActive) reading.cancel()
                withContext(NonCancellable) {reading.releaseWhenIdle()}
                if(generation.get()==token) mutable.update {it.copy(active=false,overlayReady=false)}
            }
        }.also {work=it}
    }
    fun pause() {refresh.incrementAndGet();mutable.update {it.copy(paused=!it.paused,overlayReady=false,phase=if(it.paused)"Watching for page changes…" else "Paused · overlay hidden")}}
    fun stop() {
        generation.incrementAndGet()
        work?.cancel();reading.cancel()
        mutable.update {it.copy(active=false,paused=false,overlayReady=false,phase="Screen reading stopped")}
    }
    suspend fun close() {stop();owner.cancelAndJoin()}
}

/** A 96×96 RGB thumbnail ignores tiny cursor/blink noise while detecting scrolling and new lettering. */
internal fun readingFrameSignature(image: BufferedImage): IntArray {
    require(image.width in 1..2048 && image.height in 1..2048)
    return IntArray(96*96) {index -> image.getRGB((index%96*image.width/96).coerceAtMost(image.width-1),
        (index/96*image.height/96).coerceAtMost(image.height-1)) and 0xffffff}
}
internal fun readingFrameChanged(before: IntArray, after: IntArray): Boolean {
    require(before.size==96*96 && after.size==before.size)
    var changed=0
    for(index in before.indices) {
        val a=before[index];val b=after[index]
        val difference=abs((a shr 16 and 255)-(b shr 16 and 255))+abs((a shr 8 and 255)-(b shr 8 and 255))+abs((a and 255)-(b and 255))
        if(difference>60 && ++changed>=12)return true
    }
    return false
}
