package com.sal7one.transiber.reading

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.i18n.uiText as localizedUiText

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.sal7one.transiber.R
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.ocr.*
import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.MangaPaint
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** One projection/display, one OCR worker, bounded history and no stored screenshots. */
class ReadingOverlayService : Service() {
    private val uiText get() = this.localizedUiText()

    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val wm by lazy {getSystemService(WindowManager::class.java)}
    private val prefs by lazy {com.sal7one.transiber.preferences.UserPreferences.store(this, "reading-overlay")}
    private val overlayPreferences by lazy {ReadingOverlayPreferences(this)}
    private var options=ReadingOverlaySettings()
    private lateinit var controller: CameraOcrController
    private lateinit var voice: VoicePlayer
    private lateinit var profile: OcrProfile
    private var source="en"
    private var target="ar"
    private var snapshot: ConversationTranslatorSnapshot?=null
    private var setupReady=false
    private var setupError: String?=null
    private var sessionError: String?=null
    private var sessionSelection: OcrSelection?=null
    private val setupMutex=Mutex()
    private val captureMutex=Mutex()
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var reader: ImageReader?=null
    private var frameWaiter: CancellableContinuation<*>?=null
    private var latestImage: Image?=null
    private var imageGeneration=0L
    private var frameConsumer: ((Image)->Unit)?=null
    private var handle: ReadingOverlayHandle?=null
    private var panel: ReadingOverlayControls?=null
    private var selector: LinearLayout?=null
    private var pageView: ReadingTranslationView?=null
    private var pageWidth=0
    private var pageHeight=0
    private var layerParams=WindowManager.LayoutParams()
    private var locked=true
    private val motion=ReadingMotion()
    private var motionBaselineReady=false
    private var verifyAfterSetup=false
    private var scanMs=500L
    private var pageCrop: PixelCrop?=null
    private var selectedBitmap: Bitmap?=null
    private var status: TextView?=null
    private var original: TextView?=null
    private var translated: TextView?=null
    private var handleParams=WindowManager.LayoutParams()
    private var panelParams=WindowManager.LayoutParams()
    private var captureJob: Job?=null
    private var generation=0L
    private var working=false
    private var frameSubmitted=false
    private var manualPage=false
    private var stopping=false
    private var setupSuppressed=false
    private var recoverAfterSetup=false
    private var region: RectF?=null
    private val trigger=ReadingTrigger()
    private val pageGuard=ReadingPageGuard()
    private var pageTicket: ReadingPageGuard.Ticket?=null
    private data class CompletedPage(val state: CameraOcrState,val width: Int,val height: Int,val crop: PixelCrop,
        val colours: ReadingColourSamples?)
    private var completedPage: CompletedPage?=null
    private var paintedMasks=emptyList<PixelCrop>()
    private var previousPaintedMasks=emptyList<PixelCrop>()
    private var renderedRegions=emptyList<OcrRegion>()
    private var renderedBoxes=emptyList<TranslatedOcrBox>()
    private class PaintIdentity(val value: MangaPaint) {
        override fun equals(other: Any?)=other is PaintIdentity && value===other.value
        override fun hashCode()=System.identityHashCode(value)
    }
    private data class LayerPixels(val labels: List<ReadingRenderedLabel>,val cleanup: List<PaintIdentity>,
        val outlines: List<OcrRect>,val crop: PixelCrop,val masks: List<PixelCrop>,
        val sourceWidth: Int,val sourceHeight: Int,val viewWidth: Int,val viewHeight: Int,
        val appearance: ReadingBoxStyle,val alpha: Float)
    private var layerPixels: LayerPixels?=null
    private var lastPaintPixels: LayerPixels?=null
    private var paintRevision=0L
    private var paintChangedAt=0L
    private var paintChangedGeneration=0L
    private var pendingPaintDraw=false
    private val history=ArrayDeque<Pair<String,String>>()
    private var latest=CameraOcrState()
    private var lastHistory=""
    private var historyIndex=-1
    private var viewingBox=false
    var paused=false; private set
    private val dark get()=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get()=if(dark)Color.WHITE else Color.rgb(25,30,35)
    private val paper get()=if(dark)Color.rgb(28,32,38) else Color.rgb(250,250,250)
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun now()=SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        controller=CameraOcrController(this)
        voice=VoicePlayer(this){_,error->if(error!=null)showVoiceError(error)}
        scope.launch { com.sal7one.transiber.shortcuts.OverlaySetupVisibility.active.collect { suppressForSetup(it) } }
    }
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action!=null && projection==null){stopSelf();return START_NOT_STICKY}
        when(intent?.action) {
            "stop" -> {stopSelf();return START_NOT_STICKY}
            "pause" -> {togglePause();return START_NOT_STICKY}
            "translate" -> {requestCapture(false);return START_NOT_STICKY}
            "recover" -> {applyLayerTouch();if(setupSuppressed)recoverAfterSetup=true else showPanel();return START_NOT_STICKY}
        }
        if(projection!=null)return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,uiText(UiR.string.service_screen_reading_153b9),NotificationManager.IMPORTANCE_LOW))
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION,notification())
            @Suppress("DEPRECATION") val token=intent?.getParcelableExtra<Intent>("projection") ?: error(uiText(UiR.string.service_screen_capture_permission_is_missing_start_reading_5f3e2))
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,token)
            runningState.value=true
            projection!!.registerCallback(object:MediaProjection.Callback(){
                override fun onStop(){stopSelf()}
                override fun onCapturedContentResize(width: Int,height: Int){resize(width,height)}
            },handler)
            val size=screenSize();resize(size.first,size.second)
            display=projection!!.createVirtualDisplay("Hearth reading",reader!!.width,reader!!.height,resources.configuration.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            val selection=OcrPreferences(this).read()
            profile=selection.profile;source=selection.source;target=selection.target
            options=overlayPreferences.read()
            configureOptions(options)
            createWindows()
            scope.launch {overlayPreferences.values().collect {applyOptions(it)}}
            // Always observe the worker, including when initial configuration fails.
            // A later explicit retry can repair/reload settings without restarting capture.
            scope.launch {controller.state.collect(::onOcrState)}
            scope.launch {
                try {
                    prepareSession()
                    // Initial capture waits for the saved translator snapshot.
                    if(trigger.mode=="page")trigger.movement(now())
                }catch(e: CancellationException){throw e}
                catch(e: Exception){setupError=e.message ?: e.toString();showError(setupError!!)}
            }
            scope.launch {
                while(isActive) {
                    delay(scanMs)
                    if(!setupReady || setupError!=null || setupSuppressed || paused || selector!=null || panel?.visibility==View.VISIBLE || captureJob?.isActive==true)continue
                    if(profile.engine=="manga" && region==null)continue
                    if(trigger.mode=="manual" && pageCrop==null && !working)continue
                    captureJob=scope.launch { samplePage() }
                }
            }
        }catch(e: Exception) {Toast.makeText(this,e.message ?: e.toString(),Toast.LENGTH_LONG).show();stopSelf()}
        return START_NOT_STICKY
    }
    /** One saved OCR/translator selection for each admitted page, never an in-flight reroute. */
    private suspend fun prepareSession() = setupMutex.withLock {
        val selection=OcrPreferences(this).read()
        val config=CaptionConfigStore.config(this).first()
        val nextSnapshot=if(selection.translate) {
            val provider=ConversationTranslationSettings.provider(selection.providerId)
            val languages=provider?.let {ConversationTranslationSettings.capabilities(this,it)}
            uiText.ocrTranslationIssue(selection,selection.translationModel(config.localTranslationModelId),languages,ByokPolicy.FEATURE_BYOK)?.let {error(it)}
            ConversationTranslationSettings.snapshot(this,selection.translationModel(config.localTranslationModelId),selection.providerId)
        } else null
        check(selection.source in selection.profile.languages) {
            uiText(UiR.string.service_choose_a_text_language_supported_by_1_s_in_reading_83c65,uiText.label(selection.profile))
        }
        if(sessionSelection!=selection || snapshot!=nextSnapshot) {
            // Reuse the loaded engine on an unchanged page. A changed language/model
            // must retire its worker first; new pages never inherit an old translator.
            if(controller.state.value.running || controller.state.value.closing) {
                controller.stop()
                try {withTimeout(30_000) {controller.awaitStopped()}}
                catch(e: TimeoutCancellationException){throw IllegalStateException(uiText(UiR.string.pipeline_cleanup_timeout),e)}
            }
            pageGuard.reset();completedPage=null
            profile=selection.profile;source=selection.source;target=selection.target
            snapshot=nextSnapshot;sessionSelection=selection
        }
        setupReady=true;setupError=null
    }
    /** Progressive OCR and translation can overlap: only a fully idle page is terminal. */
    private fun onOcrState(value: CameraOcrState) {
        latest=value
        if(!working || pageTicket==null || !frameSubmitted){renderControls();return}
        if(!viewingBox && value.text.isNotBlank())original?.text=value.text
        if(value.regions.isNotEmpty())showRegions()
        // Progressive OCR can invalidate every translated box while retaining the
        // page. An empty authoritative list must also remove stale labels/cleanup.
        showTranslations(value.boxTranslations)
        if(!viewingBox)translated?.text=value.translation
        val terminal=!value.loading && !value.processing && !value.translating && !value.detecting && !value.closing
        sessionError=value.error
        status?.text=value.error ?: when {
            value.loading->uiText(UiR.string.service_loading_1_s_aba14,uiText.label(profile))
            value.processing && value.stage==com.sal7one.hearth.core.vision.PageOcrStage.SEGMENTATION->uiText(UiR.string.ocr_koharu_refining)
            value.processing && value.stage==com.sal7one.hearth.core.vision.PageOcrStage.CLEANUP->uiText(UiR.string.ocr_cleaning_background)
            value.detecting->uiText(UiR.string.ocr_detecting_regions)
            value.processing && value.regions.isNotEmpty()->uiText(UiR.string.ocr_reading_regions,value.lines.size,value.regions.size)
            value.processing->uiText(UiR.string.service_reading_text_b6863)
            value.translating->uiText(UiR.string.service_translating_ae47b)
            value.text.isNotBlank()->uiText(UiR.string.service_1_s_2_s_ms_ocr_87c9c,uiText.label(profile),value.ocrMs)
            else->uiText(UiR.string.service_open_a_reader_then_tap_translate_or_draw_area_a9c06)
        }
        if(terminal) {
            finishAttempt(value)
            if(value.error!=null)showError(value.error)
            else if(value.text.isNotBlank()) {
                val key=value.text+"\u0000"+value.translation
                if(key!=lastHistory){historyIndex=-1;lastHistory=key;history.addFirst(value.text to value.translation);while(history.size>20)history.removeLast()}
                if(value.boxTranslations.isNotEmpty())showTranslations(value.boxTranslations)
                // A successful refresh never steals focus by opening the controls.
                // Original text and history remain available through the explicit menu.
            } else status?.text=uiText(UiR.string.service_no_text_found_in_this_area_draw_a_smaller_region_o_0c8b8)
        }
        renderControls()
    }
    private fun notification(): Notification {
        fun action(id: Int,name: String)=PendingIntent.getService(this,id,Intent(this,ReadingOverlayService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(uiText(UiR.string.service_hearth_screen_reading_0905b))
            .setContentText(if(paused)uiText(UiR.string.service_paused_tap_translate_to_read_once_56c5d) else uiText(UiR.string.service_read_pages_in_another_app_screenshots_stay_local_64bd9))
            .setOngoing(true).setContentIntent(action(44,"recover"))
            .addAction(0,uiText(UiR.string.service_translate_2be17),action(41,"translate")).addAction(0,if(paused)uiText(UiR.string.service_resume_b3bd0) else uiText(UiR.string.service_pause_78196),action(42,"pause")).addAction(0,uiText(UiR.string.service_stop_9e253),action(43,"stop")).build()
    }
    private fun togglePause() {
        // A cancelled unfinished page is not a completed caption to keep on Resume.
        // Clear only uncertain geometry; the last completed page/history remains cached.
        if(!paused && working && frameSubmitted)clearPage()
        paused=!paused
        cancelAttempt();trigger.reset();voice.stop()
        // Pause retains the completed page. Resume observes real changes instead of
        // treating the button itself as a different page or throwing away its result.
        if(!paused && pageCrop==null && trigger.mode=="page")trigger.movement(now())
        renderControls();updateLayerVisibility()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    private fun resetMotion() {motion.reset();motionBaselineReady=false}
    private fun configureOptions(value: ReadingOverlaySettings) {
        options=value.bounded();locked=options.passThrough
        trigger.mode=options.mode;trigger.settleMs=options.settleMs;trigger.intervalMs=options.refreshCooldownMs
        scanMs=options.scanMs;motion.sensitivity=options.changeSensitivity
    }
    private fun applyOptions(value: ReadingOverlaySettings) {
        val old=options;configureOptions(value)
        pageView?.showOriginal=options.showOriginal
        pageView?.applyAppearance(options)
        updatePaintedMasks()
        if(old.mode!=options.mode) {
            if(working && frameSubmitted)clearPage()
            cancelAttempt();trigger.reset()
            if(options.mode=="page" && !paused && pageCrop==null)trigger.movement(now())
        }
        if(old.motionExclusions!=options.motionExclusions) {
            cancelAttempt();clearPage(forgetCompleted=true);resetMotion();trigger.reset()
        }
        // Changes to our own drawing are not reader navigation.
        if(old.showOriginal!=options.showOriginal || old.translationVisible!=options.translationVisible ||
            ReadingBoxStyle.from(old)!=ReadingBoxStyle.from(options))resetMotion()
        applyLayerTouch();updateLayerVisibility();renderControls()
    }
    private fun renderControls() {
        val state=ReadingOverlayUiState(paused=paused,working=working,passThrough=locked,
            translationsVisible=options.translationVisible,showOriginal=options.showOriginal,mode=options.mode,
            textSize=options.textSizeSp,settleMs=options.settleMs,scanMs=options.scanMs,
            refreshCooldownMs=options.refreshCooldownMs,changeSensitivity=options.changeSensitivity,
            compactHandle=options.compactHandle,selectedArea=region!=null,languagePair="${uiText.languageName(source)} → ${uiText.languageName(target)}",historyCount=history.size,error=sessionError,
            replaceText=options.replaceText,boxWidthPercent=options.boxWidthPercent,boxHeightPercent=options.boxHeightPercent,
            boxBackgroundArgb=options.boxBackgroundArgb,boxTextArgb=options.boxTextArgb,boxOpacity=options.boxOpacity,
            excludedAreas=options.motionExclusions.size,
            inactiveExcludedAreas=options.motionExclusions.count {it.cropFor(pageWidth,pageHeight)==null})
        handle?.render(state);panel?.render(state)
    }
    private fun cancelAttempt() {
        captureJob?.cancel();captureJob=null;generation++;working=false;frameSubmitted=false;manualPage=false
        pageGuard.cancel();pageTicket=null;controller.invalidate()
    }
    private fun finishAttempt(value: CameraOcrState) {
        val ticket=pageTicket ?: return
        if(!pageGuard.complete(ticket))return
        pageTicket=null;working=false;frameSubmitted=false
        completedPage=pageCrop?.takeIf {value.error==null || value.text.isNotBlank() || value.regions.isNotEmpty()}?.let {
            CompletedPage(value,pageWidth,pageHeight,it,pageView?.sourceColours)
        }
    }
    /** Track actual pixels, not repeated OCR progress/confidence/source-text emissions. */
    private fun updatePaintRevision() {
        val view=pageView
        val crop=pageCrop
        val next=if(view?.visibility==View.VISIBLE && !options.showOriginal && crop!=null &&
            (renderedBoxes.isNotEmpty() || renderedRegions.isNotEmpty())) {
            val labels=view.renderedLabels()
            val translatedBounds=renderedBoxes.map {OcrRect(it.line.x,it.line.y,it.line.width,it.line.height)}.toSet()
            val cleanup=if(options.replaceText)renderedBoxes.mapNotNull {it.line.region?.paint?.let(::PaintIdentity)} else emptyList()
            LayerPixels(labels,cleanup,renderedRegions.map {it.bounds}.filter {it !in translatedBounds},crop,
                view.motionExclusions(),pageWidth,pageHeight,view.width,view.height,ReadingBoxStyle.from(options),layerParams.alpha)
        } else null
        if(next==layerPixels)return
        // Hiding/restoring the exact same layer changes composition, not its
        // content. Preserve the stable covered-pixel reference across controls.
        if(next!=null && next!=lastPaintPixels) {paintRevision++;lastPaintPixels=next}
        layerPixels=next;paintChangedAt=now();paintChangedGeneration=imageGeneration
        pendingPaintDraw=next!=null
    }
    private fun paintIsStable(frameGeneration: Long)=layerPixels!=null && !pendingPaintDraw &&
        now()-paintChangedAt>=150 && frameGeneration>paintChangedGeneration
    private fun updateLayerVisibility() {
        pageView?.visibility=if(!setupSuppressed && selector==null && panel?.visibility!=View.VISIBLE &&
            options.translationVisible && pageCrop!=null)View.VISIBLE else View.INVISIBLE
        updatePaintRevision()
    }
    private fun restoreCompletedPage() {
        val page=completedPage ?: return
        pageWidth=page.width;pageHeight=page.height;pageCrop=page.crop
        pageView?.sourceColours=page.colours
        renderedRegions=page.state.regions;renderedBoxes=page.state.boxTranslations
        pageView?.updateRegions(page.state.regions,pageWidth,pageHeight,page.crop)
        pageView?.update(page.state.boxTranslations,pageWidth,pageHeight,page.crop)
        updatePaintedMasks();updateLayerVisibility()
        if(!viewingBox) {original?.text=page.state.text;translated?.text=page.state.translation}
    }
    private fun screenSize(): Pair<Int,Int> {
        if(Build.VERSION.SDK_INT>=30)return wm.maximumWindowMetrics.bounds.let {it.width() to it.height()}
        val metrics=android.util.DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics);return metrics.widthPixels to metrics.heightPixels
    }
    private fun resize(width: Int,height: Int) {
        if(width<=0||height<=0)return
        val scale=minOf(1f,1600f/maxOf(width,height));val w=maxOf(1,(width*scale).toInt());val h=maxOf(1,(height*scale).toInt())
        if(reader?.width==w&&reader?.height==h)return
        frameWaiter?.let {if(it.isActive)it.resumeWithException(IllegalStateException(uiText(UiR.string.service_screen_size_changed_please_capture_the_page_again_38901)))};frameWaiter=null
        display?.surface=null;reader?.setOnImageAvailableListener(null,null);latestImage?.close();latestImage=null;reader?.close()
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3).also {input->
            // Keep one latest image, with two slots available for acquireLatestImage
            // to discard older queued frames. Unused frames are never copied/OCRed.
            input.setOnImageAvailableListener({images->
                if(images!==reader || stopping)return@setOnImageAvailableListener
                try {
                    val next=images.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val previous=latestImage;latestImage=next;imageGeneration++
                    previous?.close()
                    frameConsumer?.invoke(next)
                }catch(e: Exception){frameWaiter?.let {if(it.isActive)it.resumeWithException(e)}}
            },handler)
        }
        display?.resize(w,h,resources.configuration.densityDpi)
        display?.surface=reader!!.surface
        if(::controller.isInitialized)cancelAttempt()
        clearPage(forgetCompleted=true);region=null;resetMotion();trigger.reset()
        if(setupReady && trigger.mode=="page" && !paused)trigger.movement(now())
    }
    private fun captureCrop(width: Int,height: Int,useSelectedArea: Boolean=true): PixelCrop = region?.takeIf {useSelectedArea}?.let {r->
        ReadingSelection.crop(width,height,width.toFloat(),height.toFloat(),r.left*width,r.top*height,r.right*width,r.bottom*height)
            ?: error(uiText(UiR.string.service_selected_area_is_too_small_draw_it_again_79441))
    } ?: run {
        // Status/navigation glyphs must not make a protected black page look readable.
        val screen=screenSize()
        val insets=if(Build.VERSION.SDK_INT>=30)wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) else null
        @Suppress("DEPRECATION") val top=insets?.top ?: handle?.rootWindowInsets?.stableInsetTop ?: 0
        @Suppress("DEPRECATION") val bottom=insets?.bottom ?: handle?.rootWindowInsets?.stableInsetBottom ?: 0
        PixelCrop(0,(top.toLong()*height/screen.second).toInt(),width,height-(bottom.toLong()*height/screen.second).toInt())
    }
    private suspend fun <T:Any> capture(clean: Boolean,useSelectedArea: Boolean=true,dispose: (T)->Unit={},read: (Image)->T): T = captureMutex.withLock {
        val frames=ReadingCaptureFrames()
        val began=now()
        val needsFresh=clean && listOfNotNull(handle,panel,pageView).any {it.isShown}
        val hiddenGeneration=imageGeneration
        var blankCandidate: T?=null
        if(clean){handle?.visibility=View.INVISIBLE;panel?.visibility=View.GONE;pageView?.visibility=View.INVISIBLE;updatePaintRevision()}
        try {
            if(clean)delay(100) // Allow Android to compose the hidden overlay before inspecting the buffer.
            checkNotNull(reader){uiText(UiR.string.service_screen_capture_has_stopped_c682a)}
            checkNotNull(display){uiText(UiR.string.service_screen_capture_has_stopped_c682a)}
            return@withLock withTimeout(5000) {suspendCancellableCoroutine {continuation: CancellableContinuation<T>->
                frameWaiter=continuation
                fun inspect(image: Image) {
                    if(!continuation.isActive || needsFresh && imageGeneration<=hiddenGeneration)return
                    try {
                        val plane=image.planes[0]
                        val decision=frames.observe(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride,
                            captureCrop(image.width,image.height,useSelectedArea),image.timestamp)
                        when(decision) {
                            ReadingCaptureFrames.Decision.STALE->Unit
                            ReadingCaptureFrames.Decision.BLACK->{blankCandidate?.let(dispose);blankCandidate=null}
                            ReadingCaptureFrames.Decision.BLANK->{
                                val candidate=read(image)
                                blankCandidate?.let(dispose);blankCandidate=candidate
                            }
                            ReadingCaptureFrames.Decision.READABLE->{
                                val result=read(image)
                                blankCandidate?.let(dispose);blankCandidate=null
                                continuation.resume(result) {dispose(result)}
                            }
                        }
                    }catch(e: Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                }
                frameConsumer=::inspect
                latestImage?.let(::inspect)
            }}
        } catch(e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            // A real static blank white page may produce only one frame. Preserve
            // empty OCR semantics without accepting initial white before actual text.
            blankCandidate?.let {blankCandidate=null;return@withLock it}
            val message=if(frames.rejectedBlack>0)uiText(UiR.string.service_the_captured_area_is_black_check_that_the_reader_i_87046)
                else uiText(UiR.string.reading_capture_timeout)
            throw IllegalStateException(message,e)
        } finally {
            blankCandidate?.let(dispose)
            frameConsumer=null;frameWaiter=null
            android.util.Log.d("HearthReadingCapture","kind=${if(clean) "clean" else "motion"} hardware=${Build.HARDWARE} width=${reader?.width} height=${reader?.height} frames=${frames.frames} black=${frames.rejectedBlack} range=${frames.minimum}..${frames.maximum} elapsedMs=${now()-began}")
            if(clean && !stopping && !setupSuppressed && selector==null && panel?.visibility!=View.VISIBLE)handle?.visibility=View.VISIBLE
        }
    }
    private suspend fun screenshot(clean: Boolean=true,useSelectedArea: Boolean=true): Bitmap = capture(clean,useSelectedArea=useSelectedArea,dispose={it.recycle()}) { image ->
        val plane=image.planes[0];check(plane.pixelStride==4){uiText(UiR.string.service_unsupported_screen_pixel_stride_1_s_69dba, plane.pixelStride)}
        // Absolute RGBA reads preserve a retained Image's buffer position, honor
        // row padding (including an unpadded last row), and make OCR pixels opaque.
        Bitmap.createBitmap(ReadingCaptureFrames.copyOpaqueArgb(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride),
            image.width,image.height,Bitmap.Config.ARGB_8888)
    }
    private data class MotionFrame(val pixels: IntArray,val width: Int,val height: Int,val generation: Long)
    private suspend fun motionFrame(): MotionFrame = capture(false) { image ->
        val plane=image.planes[0]
        check(plane.pixelStride==4){uiText(UiR.string.service_unsupported_screen_pixel_stride_1_s_69dba, plane.pixelStride)}
        MotionFrame(ReadingMotionSampler.sample(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride),image.width,image.height,imageGeneration)
    }
    private fun signature(bitmap: Bitmap, excludedAreas: List<PixelCrop> = emptyList()): IntArray {
        val tiny=Bitmap.createScaledBitmap(bitmap,32,48,false)
        return IntArray(32*48).also {pixels->tiny.getPixels(pixels,0,32,0,0,32,48);pixels.indices.forEach {i->
            val x=(i%32+.5)*bitmap.width/32;val y=(i/32+.5)*bitmap.height/48
            val ignored=excludedAreas.any {x>=it.left && x<it.right && y>=it.top && y<it.bottom}
            val p=pixels[i];pixels[i]=if(ignored)127 else (Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10
        };if(tiny!==bitmap)tiny.recycle()}
    }
    private fun ignoredAreas(width: Int,height: Int)=options.motionExclusions.mapNotNull {it.cropFor(width,height)}
    /** Window elevation is drawn outside View bounds (Samsung adds 2*elevation outsets). */
    private fun controlMotionCrop(view: View,frameWidth: Int,frameHeight: Int): PixelCrop {
        val position=IntArray(2);view.getLocationOnScreen(position)
        val screen=screenSize()
        val shadow=maxOf(dp(16),kotlin.math.ceil(view.elevation*2).toInt()+dp(4))
        return PixelCrop(((position[0]-shadow).coerceAtLeast(0).toLong()*frameWidth/screen.first).toInt(),
            ((position[1]-shadow).coerceAtLeast(0).toLong()*frameHeight/screen.second).toInt(),
            ((position[0]+view.width+shadow).coerceAtMost(screen.first).toLong()*frameWidth/screen.first).toInt(),
            ((position[1]+view.height+shadow).coerceAtMost(screen.second).toLong()*frameHeight/screen.second).toInt())
    }
    private suspend fun samplePage() {
        try {
            val frame=motionFrame()
            if(paused || setupSuppressed || selector!=null || panel?.visibility==View.VISIBLE)return
            val masks=mutableListOf(PixelCrop(0,0,frame.width,(frame.height*.055f).toInt()),
                PixelCrop(0,(frame.height*.94f).toInt(),frame.width,frame.height))
            handle?.let {masks+=controlMotionCrop(it,frame.width,frame.height)}
            masks+=ignoredAreas(frame.width,frame.height)
            val visibleMasks=if(pageView?.visibility==View.VISIBLE && !options.showOriginal)paintedMasks else emptyList()
            // Hard masks are controls/system chrome. Covered source text remains
            // observable through the layer's <=.8 alpha once our own painting settles.
            val covered=mutableListOf<PixelCrop>()
            for(mask in visibleMasks+previousPaintedMasks)if(pageWidth>0 && pageHeight>0) {
                covered+=PixelCrop(mask.left*frame.width/pageWidth,mask.top*frame.height/pageHeight,
                    mask.right*frame.width/pageWidth,mask.bottom*frame.height/pageHeight)
            }
            val paintStable=paintIsStable(frame.generation)
            val moved=motion.observe(frame.pixels,frame.width,frame.height,masks,covered,paintRevision,paintStable,layerParams.alpha)
            motionBaselineReady=true
            previousPaintedMasks=visibleMasks
            if(moved) {
                if(com.sal7one.transiber.BuildConfig.DEBUG || com.sal7one.transiber.BuildConfig.BUILD_TYPE=="qa") {
                    android.util.Log.d("HearthReadingMotion","${motion.lastDiagnostics} paintRevision=$paintRevision stable=$paintStable pendingDraw=$pendingPaintDraw paintAgeMs=${now()-paintChangedAt} frameGeneration=${frame.generation} paintGeneration=$paintChangedGeneration alpha=${layerParams.alpha} regions=${renderedRegions.size} boxes=${renderedBoxes.size} hardMasks=${masks.size} coveredMasks=${covered.size} working=$working")
                }
                onPageMotion(moved,motion.lastDiagnostics.kind)
            }
            if(trigger.ready(now()) && !working) {
                val clean=screenshot()
                if(paused || setupSuppressed || selector!=null || panel?.visibility==View.VISIBLE){clean.recycle();return}
                trigger.accepted(now());submit(clean,manual=false)
            }
        }catch(e: CancellationException){throw e}
        catch(e: Exception){paused=true;showError(e.message ?: e.toString());renderControls()}
    }
    /** A manual request owns its captured page through localized animations and late DOM updates. */
    private fun onPageMotion(moved: Boolean, kind: ReadingMotion.ChangeKind) {
        if(!moved || working && manualPage && kind!=ReadingMotion.ChangeKind.WIDESPREAD)return
        clearPage();trigger.movement(now());generation++;working=false;frameSubmitted=false;manualPage=false
        pageGuard.cancel();pageTicket=null;controller.invalidate()
        original?.text="";translated?.text="";renderControls()
    }
    fun requestCapture(draw: Boolean,wholePage: Boolean=false) {
        if(setupSuppressed || stopping || projection==null || selector!=null)return
        val previousCapture=captureJob
        cancelAttempt();manualPage=true;voice.stop();resumeReader();sessionError=null;working=true;renderControls()
        if(wholePage){region=null;pageGuard.reset();completedPage=null}
        captureJob=scope.launch {
            try {
                // Manual taps have priority over passive motion sampling. Wait for
                // its listener/surface cleanup before starting a replacement capture.
                previousCapture?.join()
                prepareSession()
                val bitmap=screenshot(useSelectedArea=!draw);trigger.accepted(now())
                if(draw || (profile.engine=="manga"&&region==null))selectRegion(bitmap) else submit(bitmap,manual=true)
            }catch(e: CancellationException){throw e}
            catch(e: Exception){showError(e.message ?: e.toString())}
        }
    }
    private suspend fun submit(bitmap: Bitmap, manual: Boolean=false) {
        var input: Bitmap?=null
        try {
            prepareSession()
            check(setupReady){setupError ?: uiText(UiR.string.service_reading_settings_are_still_loading_tap_retry_in_a__cd6c6)}
            check(source in profile.languages){uiText(UiR.string.service_choose_a_text_language_supported_by_1_s_in_reading_83c65, uiText.label(profile))}
            val crop=captureCrop(bitmap.width,bitmap.height)
            val cropped=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width,crop.height)
            input=if(cropped===bitmap)bitmap.copy(Bitmap.Config.ARGB_8888,false) else cropped
            val excluded=OcrExcludedAreas.localize(ignoredAreas(bitmap.width,bitmap.height),crop)
            // Check the actual capture before normalizing excluded signature pixels.
            check(signature(input).any {it>8}){uiText(UiR.string.service_the_captured_area_is_black_check_that_the_reader_i_87046)}
            val pixels=signature(input,excluded)
            val ticket=pageGuard.begin(pixels,manual)
            if(ticket==null) {
                input.recycle();bitmap.recycle();restoreCompletedPage();renderControls();return
            }
            pageTicket=ticket;manualPage=manual;sessionError=null
            original?.text="";translated?.text="";status?.text=uiText(UiR.string.service_reading_1_s_30d82, uiText.label(profile))
            val epoch=generation
            if(controller.state.value.closing)controller.awaitStopped()
            if(epoch!=generation || !pageGuard.isCurrent(ticket)){input.recycle();bitmap.recycle();return}
            clearPage();pageWidth=bitmap.width;pageHeight=bitmap.height;pageCrop=crop
            // Capture() hid every Hearth window before these pixels were admitted.
            // Retain only a bounded colour grid; the OCR worker still owns/recycles input.
            pageView?.setSourceImage(checkNotNull(input));bitmap.recycle()
            viewingBox=false;working=true;frameSubmitted=true;renderControls()
            if(!controller.state.value.running)controller.start(profile,source,target,snapshot,false,input,positioned=true,excludedAreas=excluded)
            else controller.offer(input,true,excludedAreas=excluded)
        }catch(e: CancellationException){
            input?.takeUnless {it.isRecycled}?.recycle()
            if(!bitmap.isRecycled)bitmap.recycle()
            throw e
        }catch(e: Exception){
            input?.takeUnless {it.isRecycled}?.recycle()
            pageTicket?.let {pageGuard.complete(it)};pageTicket=null;completedPage=null
            clearPage();if(!bitmap.isRecycled)bitmap.recycle()
            throw e
        }
    }
    private fun suppressForSetup(suppressed: Boolean) {
        if(setupSuppressed==suppressed)return
        setupSuppressed=suppressed
        if(suppressed) {
            verifyAfterSetup=working
            cancelAttempt();voice.stop();trigger.reset()
            // Keep the last output and area while Hearth owns the foreground.
            // Returning from settings must not look like a fresh reader session.
            panel?.visibility=View.GONE
            panelParams.flags=panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            panel?.let {wm.updateViewLayout(it,panelParams)}
            handle?.visibility=View.GONE;pageView?.visibility=View.INVISIBLE;selector?.visibility=View.GONE;updatePaintRevision()
        } else if(!stopping) {
            // Preserve an observed reader baseline across Hearth navigation. Replacing
            // it with the first returning frame would hide a page change underneath.
            // If controls changed the baseline, or unfinished work was cancelled,
            // remove uncertain geometry; automatic mode verifies via clean-page dedupe.
            val uncertain=verifyAfterSetup || !motionBaselineReady
            verifyAfterSetup=false
            if(uncertain) {
                clearPage()
                if(sessionError==null)status?.text=uiText(UiR.string.service_open_a_reader_then_tap_translate_or_draw_area_a9c06)
            }
            if(selector!=null)selector?.visibility=View.VISIBLE else handle?.visibility=View.VISIBLE
            updateLayerVisibility();renderControls()
            if(setupReady && pageCrop==null && trigger.mode=="page" && !paused)trigger.movement(now())
            if(recoverAfterSetup){recoverAfterSetup=false;showPanel()}
        }
    }
    private fun clearPage(forgetCompleted: Boolean=false) {
        if(paintedMasks.isNotEmpty())previousPaintedMasks=paintedMasks
        paintedMasks=emptyList();renderedRegions=emptyList();renderedBoxes=emptyList()
        if(lastPaintPixels!=null){lastPaintPixels=null;paintRevision++}
        pageView?.update(emptyList());pageView?.updateRegions(emptyList());pageView?.visibility=View.INVISIBLE;pageCrop=null;updatePaintRevision()
        if(forgetCompleted){completedPage=null;pageView?.sourceColours=null;pageGuard.reset();pageTicket=null}
    }
    private fun resumeReader() {
        panel?.visibility=View.GONE
        panelParams.flags=panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        panel?.let {wm.updateViewLayout(it,panelParams)}
        handle?.visibility=if(setupSuppressed)View.GONE else View.VISIBLE
        applyLayerTouch();updateLayerVisibility()
        // Closing controls is navigation, not another OCR request. The next normal
        // motion sample can still detect a real page change made while options were open.
    }
    private fun applyLayerTouch() {
        layerParams.flags=layerParams.flags.let {if(locked)it or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else it and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()}
        layerParams.alpha=if(locked && Build.VERSION.SDK_INT>=31)minOf(.8f,getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch) else .8f
        pageView?.let {wm.updateViewLayout(it,layerParams)}
        updatePaintRevision()
        handle?.let {root->
            handleParams.width=dp(if(options.compactHandle)156 else 240);handleParams.height=WindowManager.LayoutParams.WRAP_CONTENT
            val bounds=screenSize()
            handleParams.x=handleParams.x.coerceIn(0,maxOf(0,bounds.first-handleParams.width))
            handleParams.y=handleParams.y.coerceIn(dp(24),maxOf(dp(24),bounds.second-dp(if(options.compactHandle)80 else 144)))
            wm.updateViewLayout(root,handleParams)
        }
    }
    private fun updatePaintedMasks() {
        // Include cleanup masks and patches, which can extend past OCR text bounds.
        paintedMasks=pageView?.motionExclusions().orEmpty()
    }
    private fun showTranslations(boxes: List<TranslatedOcrBox> = latest.boxTranslations) {
        if(setupSuppressed)return
        val crop=pageCrop ?: return
        renderedBoxes=boxes
        pageView?.update(boxes,pageWidth,pageHeight,crop)
        updatePaintedMasks();updateLayerVisibility()
    }
    private fun showRegions() {
        if(setupSuppressed)return
        val crop=pageCrop ?: return
        renderedRegions=latest.regions
        pageView?.updateRegions(latest.regions,pageWidth,pageHeight,crop)
        updatePaintedMasks();updateLayerVisibility()
    }
    private fun params(width: Int,height: Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.LEFT}
    private fun text(value: String,size: Float=16f)=TextView(this).apply {text=value;textSize=size;setTextColor(ink);setPadding(dp(4),dp(4),dp(4),dp(4))}
    private fun button(label: String,action: ()->Unit)=Button(this).apply {text=label;isAllCaps=false;minHeight=dp(48);setTextColor(ink);backgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)Color.rgb(60,68,76) else Color.rgb(224,232,237));setOnClickListener{action()}}
    private fun row(parent: LinearLayout,vararg actions: Pair<String,()->Unit>) {
        val row=LinearLayout(this);actions.forEach {(label,action)->row.addView(button(label,action),LinearLayout.LayoutParams(0,-2,1f))};parent.addView(row)
    }
    private fun createWindows() {
        val size=screenSize()
        layerParams=params(size.first,size.second).apply {
            flags=flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if(Build.VERSION.SDK_INT>=30) {setFitInsetsTypes(0);layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS}
        }
        pageView=ReadingTranslationView(this) {box->
            viewingBox=true;original?.text=box.line.text;translated?.text=box.translation
            status?.text=uiText(UiR.string.service_selected_text_box_3e276);showPanel();panel?.showReadingText()
        }.also {view->
            view.showOriginal=options.showOriginal;view.applyAppearance(options)
            view.viewTreeObserver.addOnDrawListener {
                updatePaintedMasks()
                updatePaintRevision()
                if(pendingPaintDraw) {
                    // The next composed capture must postdate this actual traversal,
                    // not just the setter that scheduled it.
                    paintChangedAt=now();paintChangedGeneration=imageGeneration;pendingPaintDraw=false
                }
            }
            wm.addView(view,layerParams);view.visibility=View.INVISIBLE
        }
        handleParams=params(dp(if(options.compactHandle)156 else 240),-2).apply {
            x=prefs.getInt("x",dp(8)).coerceIn(0,maxOf(0,size.first-width))
            y=prefs.getInt("y",dp(100)).coerceIn(dp(24),maxOf(dp(24),size.second-dp(160)))
        }
        handle=ReadingOverlayHandle(this,{requestCapture(false)},{showPanel()}).also {root->
            var sx=0f;var sy=0f;var x=0;var y=0
            root.dragTarget.setOnClickListener {showPanel()}
            root.dragTarget.setOnTouchListener {view,event->when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{sx=event.rawX;sy=event.rawY;x=handleParams.x;y=handleParams.y;true}
                MotionEvent.ACTION_MOVE->{val bounds=screenSize();handleParams.x=(x+event.rawX-sx).toInt().coerceIn(0,maxOf(0,bounds.first-root.width));handleParams.y=(y+event.rawY-sy).toInt().coerceIn(dp(24),maxOf(dp(24),bounds.second-root.height-dp(24)));wm.updateViewLayout(root,handleParams);true}
                MotionEvent.ACTION_UP->{prefs.edit().putInt("x",handleParams.x).putInt("y",handleParams.y).apply();if(abs(event.rawX-sx)+abs(event.rawY-sy)<dp(6))view.performClick();true}
                else->false
            }}
            wm.addView(root,handleParams)
        }
        panelParams=params(minOf(size.first-dp(24),dp(440)),minOf(size.second-dp(80),dp(720))).apply {
            x=(size.first-width)/2;y=maxOf(dp(24),size.second-height-dp(24))
        }
        val callbacks=ReadingOverlayCallbacks(
            translate={requestCapture(false)},pause={togglePause()},
            passThrough={value->overlayPreferences.update {it.copy(passThrough=value)}},
            visibility={value->overlayPreferences.update {it.copy(translationVisible=value)}},
            showOriginal={value->overlayPreferences.update {it.copy(showOriginal=value)}},
            selectArea={requestCapture(true)},wholePage={requestCapture(false,wholePage=true)},
            excludeArea={requestExclusion()},clearExclusions={overlayPreferences.update {it.copy(motionExclusions=emptyList())}},
            clear={clear()},mode={value->overlayPreferences.update {it.copy(mode=value)}},
            textSize={value->overlayPreferences.update {it.copy(textSizeSp=value)}},
            settleMs={value->overlayPreferences.update {it.copy(settleMs=value)}},
            scanMs={value->overlayPreferences.update {it.copy(scanMs=value)}},
            refreshCooldownMs={value->overlayPreferences.update {it.copy(refreshCooldownMs=value)}},
            changeSensitivity={value->overlayPreferences.update {it.copy(changeSensitivity=value)}},
            compactHandle={value->overlayPreferences.update {it.copy(compactHandle=value)}},
            appearance={value->overlayPreferences.update {it.withReadingAppearance(value)}},
            copy={getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hearth reading",translated?.text?.takeIf {it.isNotBlank()} ?: original?.text))},
            speak={sourceText,system->speak(sourceText,if(system)VoicePlaybackMode.SYSTEM else VoicePlaybackMode.DEFAULT)},
            stopVoice={voice.stop()},history={showHistory()},
            openSetup={startActivity(Intent(this,ReadingStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))},
            openHearth={startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))},
            stop={stopSelf()},dismiss={resumeReader()})
        panel=ReadingOverlayControls(this,callbacks).also {root->
            root.visibility=View.GONE
            status=root.statusText;original=root.originalText;translated=root.translatedText
            status?.text=uiText(UiR.string.service_open_a_reader_then_tap_translate_or_draw_area_a9c06)
            wm.addView(root,panelParams)
        }
        handle?.visibility=if(setupSuppressed)View.GONE else View.VISIBLE
        applyLayerTouch();renderControls()
    }
    private fun speak(originalText: Boolean,mode: VoicePlaybackMode) {
        val text=if(originalText)original?.text.toString() else translated?.text.toString()
        if(text.isNotBlank())voice.speak(text,if(originalText)source else target,mode)
    }
    private fun showHistory() {
        if(history.isEmpty()){status?.text=uiText(UiR.string.service_no_completed_pages_yet_779d8);return}
        historyIndex=(historyIndex+1)%history.size
        val entry=history.elementAt(historyIndex)
        viewingBox=true
        original?.text=entry.first;translated?.text=entry.second
        original?.scrollTo(0,0);translated?.scrollTo(0,0)
        status?.text=uiText(UiR.string.service_history_1_s_2_s_tap_history_for_the_next_page_48713, historyIndex+1, history.size)
        showPanel()
    }
    private fun clear() {
        cancelAttempt();sessionError=null;viewingBox=false;clearPage(forgetCompleted=true);trigger.reset();voice.stop()
        history.clear();historyIndex=-1;lastHistory="";original?.text="";translated?.text=""
        status?.text=uiText(UiR.string.service_cleared_tap_translate_for_the_current_page_f78c6);renderControls()
    }
    private fun showPanel() {
        if(setupSuppressed || selector!=null)return
        pageView?.visibility=View.INVISIBLE;handle?.visibility=View.GONE;updatePaintRevision()
        panelParams.flags=ReadingOverlayControls.focusableFlags(panelParams.flags)
        panel?.let {it.visibility=View.VISIBLE;wm.updateViewLayout(it,panelParams);it.requestFocus()}
        renderControls()
    }
    private fun showVoiceError(message: String) {
        // Voice playback is optional; its failure must not complete or discard
        // an independent OCR/translation request that is still running.
        status?.text=message;renderControls();showPanel()
    }
    private fun showError(message: String) {
        pageTicket?.let {pageGuard.complete(it);completedPage=null};pageTicket=null
        sessionError=message;status?.text=message;working=false;frameSubmitted=false;renderControls();updateLayerVisibility()
        // Keep the current reader in front. The handle exposes Retry and the exact
        // error is available in controls; failures must not open a focusable menu.
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }
    private fun requestExclusion() {
        if(setupSuppressed || stopping || projection==null || selector!=null || options.motionExclusions.size>=MAX_MOTION_EXCLUSIONS)return
        val previousCapture=captureJob
        cancelAttempt();manualPage=true;voice.stop();resumeReader();sessionError=null;working=true;renderControls()
        captureJob=scope.launch {
            try {
                previousCapture?.join()
                selectRegion(screenshot(useSelectedArea=false),ignoreMotion=true)
            }catch(e: CancellationException){throw e}
            catch(e: Exception){showError(e.message ?: e.toString())}
        }
    }
    private fun selectRegion(bitmap: Bitmap, ignoreMotion: Boolean=false) {
        working=false;frameSubmitted=false;renderControls()
        selectedBitmap=bitmap;handle?.visibility=View.GONE;panel?.visibility=View.GONE
        val root=ReadingBackAwareLayout(this){closeSelection()}.apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16))
            setBackgroundColor(paper)
            layoutDirection=if(uiText.locale.language=="ar")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        };selector=root
        val area=SelectionView(bitmap)
        root.addView(text(uiText(if(ignoreMotion)UiR.string.reading_exclusions_draw_title else UiR.string.service_draw_around_a_bubble_or_paragraph_0f329),20f))
        root.addView(text(uiText(if(ignoreMotion)UiR.string.reading_exclusions_draw_detail else UiR.string.service_drag_or_circle_the_text_center_and_whole_image_als_b1a82),13f))
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        row(root,uiText(UiR.string.service_center_a2391) to {area.chooseCenter()},uiText(UiR.string.service_whole_image_6bd00) to {area.whole()})
        row(root,uiText(UiR.string.service_cancel_77dfd) to {closeSelection()},uiText(if(ignoreMotion)UiR.string.reading_exclusions_save else UiR.string.service_read_area_9c448) to {
            val crop=area.crop()
            if(crop==null){Toast.makeText(this,uiText(UiR.string.service_draw_an_area_first_or_choose_center_5bc83),Toast.LENGTH_SHORT).show()}
            else if(ignoreMotion) {
                val exclusion=ReadingMotionExclusion.fromCrop(crop,bitmap.width,bitmap.height)
                if(exclusion!=null) {
                    overlayPreferences.update {it.copy(motionExclusions=it.motionExclusions+exclusion)}
                    closeSelection();applyOptions(overlayPreferences.read());requestCapture(false)
                }
            } else {
                region=RectF(crop.left.toFloat()/bitmap.width,crop.top.toFloat()/bitmap.height,crop.right.toFloat()/bitmap.width,crop.bottom.toFloat()/bitmap.height)
                val owned=bitmap.copy(Bitmap.Config.ARGB_8888,false);working=true;closeSelection();pageGuard.reset();completedPage=null
                captureJob=scope.launch {try{submit(owned,manual=true)}catch(e: CancellationException){throw e}catch(e: Exception){showError(e.message ?: e.toString())}}
            }
        })
        wm.addView(root,params(-1,-1).apply {flags=ReadingOverlayControls.focusableFlags(flags)});root.requestFocus()
    }
    private fun closeSelection(){selector?.let {wm.removeView(it)};selector=null;selectedBitmap?.recycle();selectedBitmap=null;handle?.visibility=if(setupSuppressed)View.GONE else View.VISIBLE;updateLayerVisibility();renderControls()}
    private inner class SelectionView(private val bitmap: Bitmap): View(this@ReadingOverlayService) {
        private var box: RectF?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init {contentDescription=uiText(UiR.string.service_select_a_text_area_by_drawing_center_and_whole_ima_4c4e8)}
        override fun onDraw(canvas: Canvas) {
            val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height);val dx=(width-bitmap.width*scale)/2;val dy=(height-bitmap.height*scale)/2
            canvas.drawBitmap(bitmap,null,RectF(dx,dy,dx+bitmap.width*scale,dy+bitmap.height*scale),paint)
            box?.let {paint.color=Color.rgb(0,180,150);paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(3).toFloat();canvas.drawRect(it,paint);paint.style=Paint.Style.FILL}
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->box=RectF(event.x,event.y,event.x,event.y)
                MotionEvent.ACTION_MOVE->box?.let {it.left=minOf(it.left,event.x);it.top=minOf(it.top,event.y);it.right=maxOf(it.right,event.x);it.bottom=maxOf(it.bottom,event.y)}
                MotionEvent.ACTION_UP->performClick()
            };invalidate();return true
        }
        override fun performClick(): Boolean {super.performClick();return true}
        fun crop()=box?.let {ReadingSelection.crop(bitmap.width,bitmap.height,width.toFloat(),height.toFloat(),it.left,it.top,it.right,it.bottom)}
        fun chooseCenter(){box=RectF(width*.2f,height*.2f,width*.8f,height*.8f);invalidate()}
        fun whole(){box=RectF(0f,0f,width.toFloat(),height.toFloat());invalidate()}
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        com.sal7one.transiber.caption.CaptionConfigStore.onOrientationChanged(newConfig.orientation)
        cancelAttempt();clearPage(forgetCompleted=true);val bounds=screenSize();region=null;resetMotion();trigger.reset()
        layerParams.width=bounds.first;layerParams.height=bounds.second;pageView?.let {wm.updateViewLayout(it,layerParams)}
        if(Build.VERSION.SDK_INT<34)resize(bounds.first,bounds.second)
        closeSelection();handleParams.x=dp(8);handleParams.y=dp(70);handle?.let {wm.updateViewLayout(it,handleParams)}
        panelParams.width=minOf(bounds.first-dp(24),dp(440));panelParams.height=minOf(bounds.second-dp(80),dp(720))
        panelParams.x=(bounds.first-panelParams.width)/2;panelParams.y=maxOf(dp(24),bounds.second-panelParams.height-dp(24));resumeReader();renderControls()
        if(setupReady && trigger.mode=="page" && !paused)trigger.movement(now())
    }
    override fun onDestroy() {
        runningState.value=false
        stopping=true;clearPage()
        frameWaiter?.cancel();frameWaiter=null;scope.cancel();controller.close();voice.close()
        display?.release();display=null;reader?.setOnImageAvailableListener(null,null);latestImage?.close();latestImage=null;reader?.close();reader=null;projection?.stop();projection=null
        listOfNotNull(selector,panel,handle,pageView).forEach {wm.removeView(it)};selectedBitmap?.recycle();selectedBitmap=null
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        private val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState
        /** A new projection must use the newly saved reader/translator selection.
         * Wait for the old service to unpublish before awaiting its native lease. */
        suspend fun stopAndAwait(context: android.content.Context) {
            context.stopService(Intent(context, ReadingOverlayService::class.java))
            withTimeout(30_000) { running.first { !it } }
        }
        fun recover(context: android.content.Context) {
            context.startService(Intent(context, ReadingOverlayService::class.java).setAction("recover"))
        }

        private const val CHANNEL="reading-overlay"
        private const val NOTIFICATION=912
    }
}
