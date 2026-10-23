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
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    private var projection: MediaProjection?=null
    private var display: VirtualDisplay?=null
    private var reader: ImageReader?=null
    private var frameWaiter: CancellableContinuation<*>?=null
    private var handle: ReadingOverlayHandle?=null
    private var panel: ReadingOverlayControls?=null
    private var selector: LinearLayout?=null
    private var pageView: ReadingTranslationView?=null
    private var pageWidth=0
    private var pageHeight=0
    private var layerParams=WindowManager.LayoutParams()
    private var locked=true
    private val motion=ReadingMotion()
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
    private var stopping=false
    private var setupSuppressed=false
    private var recoverAfterSetup=false
    private var region: RectF?=null
    private val trigger=ReadingTrigger()
    private val pageGuard=ReadingPageGuard()
    private var pageTicket: ReadingPageGuard.Ticket?=null
    private var manualAttempt=false
    private data class CompletedPage(val state: CameraOcrState,val width: Int,val height: Int,val crop: PixelCrop)
    private var completedPage: CompletedPage?=null
    private var paintedMasks=emptyList<PixelCrop>()
    private var previousPaintedMasks=emptyList<PixelCrop>()
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
            display=projection!!.createVirtualDisplay("Hearth reading",reader!!.width,reader!!.height,resources.configuration.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler)
            val selection=OcrPreferences(this).read()
            profile=selection.profile;source=selection.source;target=selection.target
            options=overlayPreferences.read()
            configureOptions(options)
            createWindows()
            scope.launch {overlayPreferences.values().collect {applyOptions(it)}}
            scope.launch {
                try {
                    val config=CaptionConfigStore.config(this@ReadingOverlayService).first()
                    if(selection.translate) {
                        val provider=ConversationTranslationSettings.provider(selection.providerId)
                        val languages=provider?.let {ConversationTranslationSettings.capabilities(this@ReadingOverlayService,it)}
                        uiText.ocrTranslationIssue(selection,selection.translationModel(config.localTranslationModelId),languages,ByokPolicy.FEATURE_BYOK)?.let {error(it)}
                        snapshot=ConversationTranslationSettings.snapshot(this@ReadingOverlayService,selection.translationModel(config.localTranslationModelId),selection.providerId)
                    }
                    setupReady=true
                    // Initial capture must wait for the actual saved translator snapshot.
                    // Startup/disk delays can exceed the page-settle interval.
                    if(trigger.mode=="page")trigger.movement(now())
                    controller.state.collect {value->
                        latest=value
                        // Invalidated native work may still finish while paused, in
                        // setup, or after a newer page. Only the admitted page owns UI.
                        if(working && pageTicket!=null) {
                            if(!viewingBox && value.text.isNotBlank())original?.text=value.text
                            if(value.regions.isNotEmpty() && value.error==null)showRegions()
                            if(value.boxTranslations.isNotEmpty() && value.error==null)showTranslations(value.boxTranslations)
                            if(!viewingBox && value.translation.isNotBlank())translated?.text=value.translation
                            if(value.error!=null) {
                                finishAttempt(value)
                                showError(value.error)
                            } else {
                                status?.text=when {
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
                                if(!value.loading && !value.processing && !value.translating) {
                                    finishAttempt(value)
                                    if(value.text.isNotBlank()) {
                                        val key=value.text+"\u0000"+value.translation
                                        if(key!=lastHistory){historyIndex=-1;lastHistory=key;history.addFirst(value.text to value.translation);while(history.size>20)history.removeLast()}
                                        if(value.boxTranslations.isNotEmpty())showTranslations(value.boxTranslations)
                                        else if(manualAttempt){showPanel();panel?.showReadingText()}
                                    } else status?.text=uiText(UiR.string.service_no_text_found_in_this_area_draw_a_smaller_region_o_0c8b8)
                                }
                            }
                        }
                        renderControls()
                    }
                }catch(e: CancellationException){throw e}catch(e: Exception){setupError=e.message ?: e.toString();showError(setupError!!)}
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
    private fun notification(): Notification {
        fun action(id: Int,name: String)=PendingIntent.getService(this,id,Intent(this,ReadingOverlayService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(uiText(UiR.string.service_hearth_screen_reading_0905b))
            .setContentText(if(paused)uiText(UiR.string.service_paused_tap_translate_to_read_once_56c5d) else uiText(UiR.string.service_read_pages_in_another_app_screenshots_stay_local_64bd9))
            .setOngoing(true).setContentIntent(action(44,"recover"))
            .addAction(0,uiText(UiR.string.service_translate_2be17),action(41,"translate")).addAction(0,if(paused)uiText(UiR.string.service_resume_b3bd0) else uiText(UiR.string.service_pause_78196),action(42,"pause")).addAction(0,uiText(UiR.string.service_stop_9e253),action(43,"stop")).build()
    }
    private fun togglePause() {
        paused=!paused
        cancelAttempt();trigger.reset();voice.stop()
        // Pause retains the completed page. Resume observes real changes instead of
        // treating the button itself as a different page or throwing away its result.
        if(!paused && completedPage==null && trigger.mode=="page")trigger.movement(now())
        renderControls();updateLayerVisibility()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    private fun configureOptions(value: ReadingOverlaySettings) {
        options=value.bounded();locked=options.passThrough
        trigger.mode=options.mode;trigger.settleMs=options.settleMs;trigger.intervalMs=options.refreshCooldownMs
        scanMs=options.scanMs;motion.sensitivity=options.changeSensitivity
    }
    private fun applyOptions(value: ReadingOverlaySettings) {
        val old=options;configureOptions(value)
        pageView?.showOriginal=options.showOriginal
        pageView?.textSizeSp=options.textSizeSp
        updatePaintedMasks()
        if(old.mode!=options.mode) {
            cancelAttempt();trigger.reset()
            if(options.mode=="page" && !paused && completedPage==null)trigger.movement(now())
        }
        // Changes to our own drawing are not reader navigation.
        if(old.showOriginal!=options.showOriginal || old.translationVisible!=options.translationVisible)motion.reset()
        applyLayerTouch();updateLayerVisibility();renderControls()
    }
    private fun renderControls() {
        val state=ReadingOverlayUiState(paused=paused,working=working,passThrough=locked,
            translationsVisible=options.translationVisible,showOriginal=options.showOriginal,mode=options.mode,
            textSize=options.textSizeSp,settleMs=options.settleMs,scanMs=options.scanMs,
            refreshCooldownMs=options.refreshCooldownMs,changeSensitivity=options.changeSensitivity,
            compactHandle=options.compactHandle,selectedArea=region!=null,languagePair="${uiText.languageName(source)} → ${uiText.languageName(target)}",historyCount=history.size)
        handle?.render(state);panel?.render(state)
    }
    private fun cancelAttempt() {
        captureJob?.cancel();captureJob=null;generation++;working=false
        pageGuard.cancel();pageTicket=null;controller.invalidate()
    }
    private fun finishAttempt(value: CameraOcrState) {
        val ticket=pageTicket ?: return
        if(!pageGuard.complete(ticket))return
        pageTicket=null;working=false
        completedPage=pageCrop?.takeIf {value.error==null}?.let {CompletedPage(value,pageWidth,pageHeight,it)}
    }
    private fun updateLayerVisibility() {
        pageView?.visibility=if(!setupSuppressed && selector==null && panel?.visibility!=View.VISIBLE &&
            options.translationVisible && pageCrop!=null)View.VISIBLE else View.INVISIBLE
    }
    private fun restoreCompletedPage() {
        val page=completedPage ?: return
        pageWidth=page.width;pageHeight=page.height;pageCrop=page.crop
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
        display?.surface=null;reader?.close();reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        display?.resize(w,h,resources.configuration.densityDpi)
        if(::controller.isInitialized)cancelAttempt()
        clearPage(forgetCompleted=true);region=null;motion.reset();trigger.reset()
        if(setupReady && trigger.mode=="page" && !paused)trigger.movement(now())
    }
    private suspend fun <T> capture(clean: Boolean, read: (Image)->T): T {
        if(clean){handle?.visibility=View.INVISIBLE;panel?.visibility=View.GONE;pageView?.visibility=View.INVISIBLE}
        try {
            if(clean)delay(100) // Only OCR needs a clean frame; motion sampling leaves translations visible.
            val input=checkNotNull(reader){uiText(UiR.string.service_screen_capture_has_stopped_c682a)}
            while(true){val old=input.acquireLatestImage() ?: break;old.close()}
            return withTimeout(5000) {suspendCancellableCoroutine {continuation: CancellableContinuation<T>->
                frameWaiter=continuation
                input.setOnImageAvailableListener({images->
                    val image=images.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if(!continuation.isActive)return@setOnImageAvailableListener
                        continuation.resume(read(image))
                    }catch(e: Exception){if(continuation.isActive)continuation.resumeWithException(e)}finally{image.close()}
                },handler)
                display?.surface=input.surface ?: error(uiText(UiR.string.service_screen_capture_has_stopped_c682a))
            }}
        } finally {
            frameWaiter=null;reader?.setOnImageAvailableListener(null,null);display?.surface=null
            if(clean && !stopping && !setupSuppressed && selector==null && panel?.visibility!=View.VISIBLE)handle?.visibility=View.VISIBLE
        }
    }
    private suspend fun screenshot(clean: Boolean=true): Bitmap = capture(clean) { image ->
        val plane=image.planes[0];check(plane.pixelStride==4){uiText(UiR.string.service_unsupported_screen_pixel_stride_1_s_69dba, plane.pixelStride)}
        val paddedWidth=plane.rowStride/4
        val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped=Bitmap.createBitmap(padded,0,0,image.width,image.height)
        if(cropped!==padded)padded.recycle()
        cropped
    }
    private data class MotionFrame(val pixels: IntArray,val width: Int,val height: Int)
    private suspend fun motionFrame(): MotionFrame = capture(false) { image ->
        val plane=image.planes[0]
        check(plane.pixelStride==4){uiText(UiR.string.service_unsupported_screen_pixel_stride_1_s_69dba, plane.pixelStride)}
        MotionFrame(ReadingMotionSampler.sample(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride),image.width,image.height)
    }
    private fun signature(bitmap: Bitmap): IntArray {
        val tiny=Bitmap.createScaledBitmap(bitmap,32,48,false)
        return IntArray(32*48).also {pixels->tiny.getPixels(pixels,0,32,0,0,32,48);pixels.indices.forEach {i->val p=pixels[i];pixels[i]=(Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10};if(tiny!==bitmap)tiny.recycle()}
    }
    private suspend fun samplePage() {
        try {
            val frame=motionFrame()
            if(paused || setupSuppressed || selector!=null || panel?.visibility==View.VISIBLE)return
            val masks=mutableListOf(PixelCrop(0,0,frame.width,(frame.height*.055f).toInt()),
                PixelCrop(0,(frame.height*.94f).toInt(),frame.width,frame.height))
            handle?.let { view ->
                val position=IntArray(2);view.getLocationOnScreen(position);val screen=screenSize()
                masks+=PixelCrop(position[0]*frame.width/screen.first,position[1]*frame.height/screen.second,
                    (position[0]+view.width)*frame.width/screen.first,(position[1]+view.height)*frame.height/screen.second)
            }
            val visibleMasks=if(pageView?.visibility==View.VISIBLE && !options.showOriginal)paintedMasks else emptyList()
            // Keep the previous drawing excluded for its disappearance frame too.
            // Geometry is independent of controller.invalidate(), which clears its arrays.
            for(mask in visibleMasks+previousPaintedMasks)if(pageWidth>0 && pageHeight>0) {
                masks+=PixelCrop(mask.left*frame.width/pageWidth,mask.top*frame.height/pageHeight,
                    mask.right*frame.width/pageWidth,mask.bottom*frame.height/pageHeight)
            }
            val moved=motion.observe(frame.pixels,frame.width,frame.height,masks)
            previousPaintedMasks=visibleMasks
            if(moved) {
                clearPage();trigger.movement(now());generation++;working=false
                pageGuard.cancel();pageTicket=null;controller.invalidate()
                original?.text="";translated?.text="";renderControls()
            }
            if(trigger.ready(now()) && !working) {
                val clean=screenshot()
                if(paused || setupSuppressed || selector!=null || panel?.visibility==View.VISIBLE){clean.recycle();return}
                trigger.accepted(now());submit(clean,manual=false)
            }
        }catch(e: CancellationException){throw e}
        catch(e: Exception){paused=true;showError(e.message ?: e.toString());renderControls()}
    }
    fun requestCapture(draw: Boolean,wholePage: Boolean=false) {
        if(setupSuppressed || stopping || projection==null || captureJob?.isActive==true || selector!=null)return
        cancelAttempt();voice.stop();resumeReader()
        if(wholePage){region=null;pageGuard.reset();completedPage=null}
        captureJob=scope.launch {
            try {
                val bitmap=screenshot();trigger.accepted(now())
                if(draw || (profile.engine=="manga"&&region==null))selectRegion(bitmap) else submit(bitmap,manual=true)
            }catch(e: CancellationException){throw e}
            catch(e: Exception){showError(e.message ?: e.toString())}
        }
    }
    private suspend fun submit(bitmap: Bitmap, manual: Boolean=false) {
        var input: Bitmap?=null
        try {
            check(setupReady){setupError ?: uiText(UiR.string.service_reading_settings_are_still_loading_tap_retry_in_a__cd6c6)}
            check(source in profile.languages){uiText(UiR.string.service_choose_a_text_language_supported_by_1_s_in_reading_83c65, uiText.label(profile))}
            val crop=region?.let {r->
                ReadingSelection.crop(bitmap.width,bitmap.height,bitmap.width.toFloat(),bitmap.height.toFloat(),r.left*bitmap.width,r.top*bitmap.height,r.right*bitmap.width,r.bottom*bitmap.height) ?: error(uiText(UiR.string.service_selected_area_is_too_small_draw_it_again_79441))
            } ?: run {
                // System status/navigation glyphs are not reader text.
                val screen=screenSize()
                val insets=if(Build.VERSION.SDK_INT>=30)wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) else null
                @Suppress("DEPRECATION") val top=insets?.top ?: handle?.rootWindowInsets?.stableInsetTop ?: 0
                @Suppress("DEPRECATION") val bottom=insets?.bottom ?: handle?.rootWindowInsets?.stableInsetBottom ?: 0
                PixelCrop(0,(top.toLong()*bitmap.height/screen.second).toInt(),bitmap.width,
                    bitmap.height-(bottom.toLong()*bitmap.height/screen.second).toInt())
            }
            val cropped=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width,crop.height)
            input=if(cropped===bitmap)bitmap.copy(Bitmap.Config.ARGB_8888,false) else cropped
            val pixels=signature(input)
            check(pixels.any {it>8}){uiText(UiR.string.service_the_captured_area_is_black_check_that_the_reader_i_87046)}
            val ticket=pageGuard.begin(pixels,manual)
            if(ticket==null) {
                input.recycle();bitmap.recycle();restoreCompletedPage();renderControls();return
            }
            pageTicket=ticket;manualAttempt=manual
            original?.text="";translated?.text="";status?.text=uiText(UiR.string.service_reading_1_s_30d82, uiText.label(profile))
            val epoch=generation
            if(latest.closing)controller.awaitStopped()
            if(epoch!=generation || !pageGuard.isCurrent(ticket)){input.recycle();bitmap.recycle();return}
            clearPage();pageWidth=bitmap.width;pageHeight=bitmap.height;pageCrop=crop;bitmap.recycle()
            viewingBox=false;working=true;renderControls()
            if(!latest.running)controller.start(profile,source,target,snapshot,false,input,positioned=true)
            else controller.offer(input,true)
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
            cancelAttempt();voice.stop();trigger.reset()
            // Keep the last output and area while Hearth owns the foreground.
            // Returning from settings must not look like a fresh reader session.
            panel?.visibility=View.GONE
            panelParams.flags=panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            panel?.let {wm.updateViewLayout(it,panelParams)}
            handle?.visibility=View.GONE;pageView?.visibility=View.INVISIBLE;selector?.visibility=View.GONE
        } else if(!stopping) {
            motion.reset()
            if(selector!=null)selector?.visibility=View.VISIBLE else handle?.visibility=View.VISIBLE
            updateLayerVisibility();renderControls()
            if(setupReady && completedPage==null && trigger.mode=="page" && !paused)trigger.movement(now())
            if(recoverAfterSetup){recoverAfterSetup=false;showPanel()}
        }
    }
    private fun clearPage(forgetCompleted: Boolean=false) {
        if(paintedMasks.isNotEmpty())previousPaintedMasks=paintedMasks
        paintedMasks=emptyList()
        pageView?.update(emptyList());pageView?.updateRegions(emptyList());pageView?.visibility=View.INVISIBLE;pageCrop=null
        if(forgetCompleted){completedPage=null;pageGuard.reset();pageTicket=null}
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
        layerParams.alpha=if(locked && Build.VERSION.SDK_INT>=31)minOf(.8f,getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch) else if(locked).8f else 1f
        pageView?.let {wm.updateViewLayout(it,layerParams)}
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
        if(boxes.isEmpty())return
        pageView?.update(boxes,pageWidth,pageHeight,crop)
        updatePaintedMasks();updateLayerVisibility()
    }
    private fun showRegions() {
        if(setupSuppressed)return
        val crop=pageCrop ?: return
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
        }.also {it.showOriginal=options.showOriginal;it.textSizeSp=options.textSizeSp;wm.addView(it,layerParams);it.visibility=View.INVISIBLE}
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
            clear={clear()},mode={value->overlayPreferences.update {it.copy(mode=value)}},
            textSize={value->overlayPreferences.update {it.copy(textSizeSp=value)}},
            settleMs={value->overlayPreferences.update {it.copy(settleMs=value)}},
            scanMs={value->overlayPreferences.update {it.copy(scanMs=value)}},
            refreshCooldownMs={value->overlayPreferences.update {it.copy(refreshCooldownMs=value)}},
            changeSensitivity={value->overlayPreferences.update {it.copy(changeSensitivity=value)}},
            compactHandle={value->overlayPreferences.update {it.copy(compactHandle=value)}},
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
        cancelAttempt();viewingBox=false;clearPage(forgetCompleted=true);trigger.reset();voice.stop()
        history.clear();historyIndex=-1;lastHistory="";original?.text="";translated?.text=""
        status?.text=uiText(UiR.string.service_cleared_tap_translate_for_the_current_page_f78c6);renderControls()
    }
    private fun showPanel() {
        if(setupSuppressed || selector!=null)return
        pageView?.visibility=View.INVISIBLE;handle?.visibility=View.GONE
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
        status?.text=message;working=false;renderControls();showPanel()
    }
    private fun selectRegion(bitmap: Bitmap) {
        selectedBitmap=bitmap;handle?.visibility=View.GONE;panel?.visibility=View.GONE
        val root=ReadingBackAwareLayout(this){closeSelection()}.apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16))
            setBackgroundColor(paper)
            layoutDirection=if(uiText.locale.language=="ar")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        };selector=root
        val area=SelectionView(bitmap)
        root.addView(text(uiText(UiR.string.service_draw_around_a_bubble_or_paragraph_0f329),20f))
        root.addView(text(uiText(UiR.string.service_drag_or_circle_the_text_center_and_whole_image_als_b1a82),13f))
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        row(root,uiText(UiR.string.service_center_a2391) to {area.chooseCenter()},uiText(UiR.string.service_whole_image_6bd00) to {area.whole()})
        row(root,uiText(UiR.string.service_cancel_77dfd) to {closeSelection()},uiText(UiR.string.service_read_area_9c448) to {
            val crop=area.crop()
            if(crop==null){Toast.makeText(this,uiText(UiR.string.service_draw_an_area_first_or_choose_center_5bc83),Toast.LENGTH_SHORT).show()}
            else {
                region=RectF(crop.left.toFloat()/bitmap.width,crop.top.toFloat()/bitmap.height,crop.right.toFloat()/bitmap.width,crop.bottom.toFloat()/bitmap.height)
                val owned=bitmap.copy(Bitmap.Config.ARGB_8888,false);closeSelection();pageGuard.reset();completedPage=null
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
        cancelAttempt();clearPage(forgetCompleted=true);val bounds=screenSize();region=null;motion.reset();trigger.reset()
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
        display?.release();display=null;reader?.close();reader=null;projection?.stop();projection=null
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
