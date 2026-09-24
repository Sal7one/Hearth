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
import java.io.File

/** One projection/display, one OCR worker, bounded history and no stored screenshots. */
class ReadingOverlayService : Service() {
    private val uiText get() = this.localizedUiText()

    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val wm by lazy {getSystemService(WindowManager::class.java)}
    private val prefs by lazy {getSharedPreferences("reading-overlay",0)}
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
    private var handle: LinearLayout?=null
    private var panel: LinearLayout?=null
    private var selector: LinearLayout?=null
    private var pageView: ReadingTranslationView?=null
    private var pageWidth=0
    private var pageHeight=0
    private var layerParams=WindowManager.LayoutParams()
    private var locked=true
    private var lockButton: ImageButton?=null
    private var handleGrip: View?=null
    private var handleControls: View?=null
    private val motion=ReadingMotion()
    private var scanMs=250L
    private var pageCrop: PixelCrop?=null
    private var selectedBitmap: Bitmap?=null
    private var status: TextView?=null
    private var original: TextView?=null
    private var translated: TextView?=null
    private var handleLabel: Button?=null
    private var pauseButton: Button?=null
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
        voice=VoicePlayer(this){_,error->if(error!=null)showError(error)}
        scope.launch { com.sal7one.transiber.shortcuts.OverlaySetupVisibility.active.collect { suppressForSetup(it) } }
    }
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action!=null && projection==null){stopSelf();return START_NOT_STICKY}
        when(intent?.action) {
            "stop" -> {stopSelf();return START_NOT_STICKY}
            "pause" -> {togglePause();return START_NOT_STICKY}
            "translate" -> {requestCapture(false);return START_NOT_STICKY}
            "recover" -> {handleParams.x=dp(8);handleParams.y=dp(100);handle?.let {wm.updateViewLayout(it,handleParams)};if(setupSuppressed)recoverAfterSetup=true else showPanel();return START_NOT_STICKY}
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
            trigger.mode=ReadingTrigger.supportedMode(prefs.getString("mode","page"));trigger.settleMs=prefs.getLong("settle",500);scanMs=prefs.getLong("scan",250).coerceIn(200,1000);if(trigger.mode=="page")trigger.movement(now())
            createWindows()
            scope.launch {
                try {
                    val config=CaptionConfigStore.config(this@ReadingOverlayService).first()
                    if(selection.translate) {
                        val provider=ConversationTranslationSettings.provider(selection.providerId)
                        val languages=provider?.let {ConversationTranslationSettings.capabilities(this@ReadingOverlayService,it)}
                        check(target in ocrTranslationTargets(selection,selection.translationModel(config.localTranslationModelId),languages,ByokPolicy.FEATURE_BYOK)) {
                            uiText(UiR.string.service_translation_does_not_support_1_s_2_s_choose_langua_d0ec6, source, target)
                        }
                        snapshot=ConversationTranslationSettings.snapshot(this@ReadingOverlayService,selection.translationModel(config.localTranslationModelId),selection.providerId)
                    }
                    setupReady=true
                    controller.state.collect {value->
                        latest=value
                        if(!viewingBox && value.text.isNotBlank()) original?.text=value.text
                        if(working && value.boxTranslations.isNotEmpty() && value.error==null)showTranslations(value.boxTranslations,automatic=true)
                        if(!viewingBox && value.translation.isNotBlank())translated?.text=value.translation
                        if(value.error!=null)showError(value.error)
                        else status?.text=when {value.loading->uiText(UiR.string.service_loading_1_s_aba14, uiText.label(profile));value.processing->uiText(UiR.string.service_reading_text_b6863);value.translating->uiText(UiR.string.service_translating_ae47b);value.text.isNotBlank()->uiText(UiR.string.service_1_s_2_s_ms_ocr_87c9c, uiText.label(profile), value.ocrMs);else->uiText(UiR.string.service_open_a_reader_then_tap_translate_or_draw_area_a9c06)}
                        if(working && !value.loading && !value.processing && !value.translating) {
                            working=false
                            handleLabel?.text=uiText(UiR.string.service_translate_2be17)
                            if(value.text.isNotBlank()) {
                                val key=value.text+"\u0000"+value.translation
                                if(key!=lastHistory){historyIndex=-1;lastHistory=key;history.addFirst(value.text to value.translation);while(history.size>20)history.removeLast()}
                                if(value.boxTranslations.isNotEmpty())showTranslations(value.boxTranslations,automatic=true) else showPanel()
                            } else if(value.error==null) {status?.text=uiText(UiR.string.service_no_text_found_in_this_area_draw_a_smaller_region_o_0c8b8);handleLabel?.text=uiText(UiR.string.service_no_text_32095)}
                        }
                    }
                }catch(e: CancellationException){throw e}catch(e: Exception){setupError=e.message ?: e.toString();showError(setupError!!)}
            }
            scope.launch {
                while(isActive) {
                    delay(scanMs)
                    if(setupSuppressed || paused || selector!=null || panel?.visibility==View.VISIBLE || captureJob?.isActive==true)continue
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
        paused=!paused;clearPage();trigger.reset();generation++;working=false;controller.invalidate();voice.stop()
        panel?.visibility=View.GONE;handle?.visibility=View.VISIBLE
        handleLabel?.text=if(paused)uiText(UiR.string.service_paused_read_989d4) else uiText(UiR.string.service_translate_2be17)
        pauseButton?.text=if(paused)uiText(UiR.string.service_resume_auto_f5626) else uiText(UiR.string.service_pause_auto_e2a61)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
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
        clearPage();region=null;motion.reset();generation++;trigger.reset()
        if(::controller.isInitialized)controller.invalidate()
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
            if(clean && !stopping && !setupSuppressed)handle?.visibility=View.VISIBLE
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
        return IntArray(32*48).also {pixels->tiny.getPixels(pixels,0,32,0,0,32,48);pixels.indices.forEach {i->val p=pixels[i];pixels[i]=(Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10};tiny.recycle()}
    }
    private suspend fun samplePage() {
        try {
            val frame=motionFrame()
            run {
                val masks=mutableListOf(PixelCrop(0,0,frame.width,(frame.height*.055f).toInt()),
                    PixelCrop(0,(frame.height*.94f).toInt(),frame.width,frame.height))
                handle?.let {view->
                    val position=IntArray(2);view.getLocationOnScreen(position);val screen=screenSize()
                    masks+=PixelCrop(position[0]*frame.width/screen.first,position[1]*frame.height/screen.second,
                        (position[0]+view.width)*frame.width/screen.first,(position[1]+view.height)*frame.height/screen.second)
                }
                pageCrop?.let {crop->
                    // Ignore our replacement pixels, including the previous frame's masks,
                    // so asynchronous layout/font changes cannot imitate reader movement.
                    if(pageView?.visibility==View.VISIBLE)latest.boxTranslations.forEach {box->
                        OcrPageLayout.box(box.line,crop,pageWidth,pageHeight,frame.width,frame.height,coverEdges=true)?.let {masks+=it}
                    }
                }
                if(motion.observe(frame.pixels,frame.width,frame.height,masks)) {
                    // observe() already advanced the baseline. Keeping it measures every
                    // moving frame; resetting here used to skip alternate scroll samples.
                    clearPage();trigger.movement(now());generation++;working=false;controller.invalidate();original?.text="";translated?.text=""
                }
            }
            if(trigger.ready(now()) && !working) {
                val clean=screenshot();trigger.accepted(now());submit(clean)
            }
        }catch(e: CancellationException){throw e}catch(e: Exception){paused=true;showError(e.message ?: e.toString())}
    }
    fun requestCapture(draw: Boolean) {
        if(setupSuppressed || stopping || projection==null || captureJob?.isActive==true || selector!=null)return
        clearPage();voice.stop();generation++;controller.invalidate();working=false
        captureJob=scope.launch {
            try {
                val bitmap=screenshot();motion.reset();trigger.accepted(now())
                if(draw || (profile.engine=="manga"&&region==null))selectRegion(bitmap) else submit(bitmap)
            }catch(e: CancellationException){throw e}catch(e: Exception){showError(e.message ?: e.toString())}
        }
    }
    private suspend fun submit(bitmap: Bitmap) {
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
            original?.text="";translated?.text="";status?.text=uiText(UiR.string.service_reading_1_s_30d82, uiText.label(profile));handleLabel?.text=uiText(UiR.string.service_reading_ae18b)
            val epoch=generation
            if(latest.closing)controller.awaitStopped()
            if(epoch!=generation){input.recycle();bitmap.recycle();return}
            clearPage();pageWidth=bitmap.width;pageHeight=bitmap.height;pageCrop=crop;bitmap.recycle()
            viewingBox=false;working=true
            if(!latest.running)controller.start(profile,source,target,snapshot,false,input,positioned=true)
            else controller.offer(input,true)
        }catch(e: Exception){
            input?.takeUnless {it.isRecycled}?.recycle()
            clearPage();if(!bitmap.isRecycled)bitmap.recycle()
            throw e
        }
    }
    private fun suppressForSetup(suppressed: Boolean) {
        setupSuppressed=suppressed
        if(suppressed) {
            captureJob?.cancel();generation++;working=false;controller.invalidate();voice.stop();clearPage()
            handle?.visibility=View.GONE;panel?.visibility=View.GONE;selector?.visibility=View.GONE
        } else if(!stopping) {
            if(selector!=null)selector?.visibility=View.VISIBLE else handle?.visibility=View.VISIBLE
            motion.reset();trigger.reset()
            if(recoverAfterSetup){recoverAfterSetup=false;showPanel()}
        }
    }
    private fun clearPage() {
        pageView?.update(emptyList());pageView?.visibility=View.INVISIBLE;pageCrop=null
    }
    private fun resumeReader() {
        panel?.visibility=View.GONE;handle?.visibility=View.VISIBLE
        locked=true;applyLayerTouch()
        // The reader may have moved while controls were open: capture again instead of
        // restoring coordinates from history or a previously visible page.
        requestCapture(false)
    }
    private fun applyLayerTouch() {
        layerParams.flags=layerParams.flags.let {if(locked)it or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else it and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()}
        layerParams.alpha=if(locked && Build.VERSION.SDK_INT>=31)minOf(.8f,getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch) else if(locked).8f else 1f
        pageView?.let {wm.updateViewLayout(it,layerParams)}
        lockButton?.setImageResource(if(locked)R.drawable.ic_reading_locked else R.drawable.ic_reading_unlocked)
        handleGrip?.visibility=if(locked)View.GONE else View.VISIBLE
        handleControls?.visibility=if(locked)View.GONE else View.VISIBLE
        handle?.let {root->
            root.setPadding(if(locked)0 else dp(10),if(locked)0 else dp(6),if(locked)0 else dp(10),if(locked)0 else dp(6))
            root.background=if(locked)null else android.graphics.drawable.GradientDrawable().apply {setColor(paper);cornerRadius=dp(16).toFloat()}
            handleParams.width=dp(if(locked)48 else 156);handleParams.height=if(locked)dp(48) else -2
            val bounds=screenSize();handleParams.x=handleParams.x.coerceIn(0,maxOf(0,bounds.first-handleParams.width))
            handleParams.y=handleParams.y.coerceIn(dp(24),maxOf(dp(24),bounds.second-dp(if(locked)72 else 184)))
            wm.updateViewLayout(root,handleParams)
        }
        lockButton?.contentDescription=if(locked)uiText(UiR.string.service_scroll_through_translations_tap_to_unlock_text_box_1eb00) else uiText(UiR.string.service_text_boxes_are_interactive_tap_to_let_touches_pass_ebaaa)
    }
    private fun showTranslations(boxes: List<TranslatedOcrBox> = latest.boxTranslations, automatic: Boolean=false) {
        if(setupSuppressed)return
        val crop=pageCrop ?: return
        if(boxes.isEmpty())return
        pageView?.update(boxes,pageWidth,pageHeight,crop)
        pageView?.visibility=if(automatic && panel?.visibility==View.VISIBLE)View.INVISIBLE else View.VISIBLE
        if(!automatic)panel?.visibility=View.GONE
        handle?.visibility=View.VISIBLE
    }
    private fun params(width: Int,height: Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.LEFT}
    private fun column()=LinearLayout(this).apply {layoutDirection=if(uiText.locale.language=="ar")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR;orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6));background=android.graphics.drawable.GradientDrawable().apply {setColor(paper);cornerRadius=dp(16).toFloat()}}
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
            status?.text=uiText(UiR.string.service_selected_text_box_3e276);showPanel()
        }.also {wm.addView(it,layerParams);it.visibility=View.INVISIBLE}
        handleParams=params(dp(156),-2).apply {x=prefs.getInt("x",dp(8)).coerceIn(0,maxOf(0,size.first-dp(156)));y=prefs.getInt("y",dp(100)).coerceIn(dp(24),maxOf(dp(24),size.second-dp(160)))}
        handle=column().also {root->
            val grip=text(uiText(UiR.string.service_hearth_drag_c53ff),12f);grip.contentDescription=uiText(UiR.string.service_move_reading_handle_drag_to_reposition_double_tap__55283)
            var sx=0f;var sy=0f;var x=0;var y=0
            grip.setOnClickListener {handleParams.x=dp(8);handleParams.y=dp(100);wm.updateViewLayout(root,handleParams)}
            val drag=View.OnTouchListener {view,event->when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{sx=event.rawX;sy=event.rawY;x=handleParams.x;y=handleParams.y;true}
                MotionEvent.ACTION_MOVE->{val bounds=screenSize();handleParams.x=(x+event.rawX-sx).toInt().coerceIn(0,maxOf(0,bounds.first-root.width));handleParams.y=(y+event.rawY-sy).toInt().coerceIn(dp(24),maxOf(dp(24),bounds.second-root.height-dp(24)));wm.updateViewLayout(root,handleParams);true}
                MotionEvent.ACTION_UP->{prefs.edit().putInt("x",handleParams.x).putInt("y",handleParams.y).apply();if(abs(event.rawX-sx)+abs(event.rawY-sy)<8)view.performClick();true}
                else->false
            }}
            grip.setOnTouchListener(drag);handleGrip=grip;root.addView(grip)
            val controls=LinearLayout(this)
            handleLabel=button(uiText(UiR.string.service_translate_2be17)){requestCapture(false)}.apply {textSize=13f}
            controls.addView(handleLabel,LinearLayout.LayoutParams(0,-2,2f))
            controls.addView(button("⋯"){showPanel()}.apply {contentDescription=uiText(UiR.string.service_reading_controls_83fa7)},LinearLayout.LayoutParams(0,-2,1f))
            handleControls=controls;root.addView(controls)
            lockButton=ImageButton(this).apply {
                setOnClickListener {locked=!locked;applyLayerTouch()}
                minimumWidth=0;minimumHeight=0
                scaleType=ImageView.ScaleType.FIT_CENTER
                background=android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(paper)},dp(8))
                // Assign after the background: InsetDrawable otherwise replaces view padding.
                setPadding(dp(14),dp(14),dp(14),dp(14))
                setOnTouchListener(drag)
            }
            root.addView(lockButton,LinearLayout.LayoutParams(dp(48),dp(48)).apply {gravity=Gravity.CENTER_HORIZONTAL})
            wm.addView(root,handleParams)
        }
        panelParams=params(minOf(size.first-dp(16),dp(440)),minOf((size.second*.60).toInt(),dp(560))).apply {x=dp(8);y=dp(60)}
        panel=column().also {root->
            row(root,uiText(UiR.string.service_read_page_33e9a) to {resumeReader()},uiText(UiR.string.service_stop_9e253) to {stopSelf()})
            status=text(uiText(UiR.string.service_open_a_reader_then_tap_translate_or_draw_area_a9c06),13f);status!!.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;root.addView(status)
            val scroll=ScrollView(this);val content=column();scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            content.addView(text(uiText(UiR.string.service_translation_ac26a),14f));translated=text("",prefs.getFloat("textSize",20f));translated!!.setTextIsSelectable(true);content.addView(translated)
            row(content,uiText(UiR.string.service_read_aloud_56210) to {speak(false,VoicePlaybackMode.DEFAULT)},uiText(UiR.string.service_android_voice_ba5fb) to {speak(false,VoicePlaybackMode.SYSTEM)})
            content.addView(text(uiText(UiR.string.service_original_c0a80),14f));original=text("");original!!.setTextIsSelectable(true);content.addView(original)
            row(content,uiText(UiR.string.service_read_original_149d2) to {speak(true,VoicePlaybackMode.DEFAULT)},uiText(UiR.string.service_stop_voice_84a32) to {voice.stop()})
            row(content,uiText(UiR.string.service_copy_af74f) to {getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hearth reading",translated?.text?.takeIf {it.isNotBlank()} ?: original?.text))},uiText(UiR.string.service_history_90ccd) to {showHistory()})
            content.addView(button(uiText(UiR.string.service_show_translations_6861c)){resumeReader()})
            row(content,uiText(UiR.string.service_translate_2be17) to {requestCapture(false)},uiText(UiR.string.service_draw_area_f80f3) to {requestCapture(true)})
            row(content,uiText(UiR.string.service_whole_page_eeb74) to {region=null;requestCapture(false)},uiText(UiR.string.service_clear_719ea) to {clear()})
            pauseButton=button(uiText(UiR.string.service_pause_auto_e2a61)){togglePause()};content.addView(pauseButton)
            row(content,uiText(UiR.string.service_smaller_text_a8ea9) to {changeText(-2)},uiText(UiR.string.service_larger_text_118f9) to {changeText(2)})
            content.addView(text(uiText(UiR.string.service_scroll_through_the_live_translations_with_the_lock_e5d32),12f))
            row(content,uiText(UiR.string.service_setup_cdd7b) to {startActivity(Intent(this,ReadingStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));stopSelf()},"Hearth" to {startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))})
            wm.addView(root,panelParams);root.visibility=View.GONE
        }
        applyLayerTouch()
    }
    private fun changeText(delta: Int) {val value=(prefs.getFloat("textSize",20f)+delta).coerceIn(14f,34f);prefs.edit().putFloat("textSize",value).apply();translated?.textSize=value}
    private fun speak(originalText: Boolean,mode: VoicePlaybackMode) {
        val text=if(originalText)original?.text.toString() else translated?.text.toString()
        if(text.isNotBlank())voice.speak(text,if(originalText)source else target,mode)
    }
    private fun showHistory() {
        if(history.isEmpty()){status?.text=uiText(UiR.string.service_no_completed_pages_yet_779d8);return}
        historyIndex=(historyIndex+1)%history.size
        val entry=history.elementAt(historyIndex)
        original?.text=entry.first;translated?.text=entry.second
        original?.scrollTo(0,0);translated?.scrollTo(0,0)
        status?.text=uiText(UiR.string.service_history_1_s_2_s_tap_history_for_the_next_page_48713, historyIndex+1, history.size)
        showPanel()
    }
    private fun clear() {viewingBox=false;clearPage();generation++;controller.invalidate();voice.stop();history.clear();historyIndex=-1;lastHistory="";original?.text="";translated?.text="";working=false;handleLabel?.text=uiText(UiR.string.service_translate_2be17);status?.text=uiText(UiR.string.service_cleared_tap_translate_for_the_current_page_f78c6)}
    private fun showPanel() {if(!setupSuppressed && selector==null){pageView?.visibility=View.INVISIBLE;panel?.visibility=View.VISIBLE;handle?.visibility=View.GONE}}
    private fun showError(message: String) {status?.text=message;working=false;handleLabel?.text=uiText(UiR.string.service_retry_9f5cd);showPanel()}
    private fun selectRegion(bitmap: Bitmap) {
        selectedBitmap=bitmap;handle?.visibility=View.GONE;panel?.visibility=View.GONE
        val root=column();selector=root
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
                val owned=bitmap.copy(Bitmap.Config.ARGB_8888,false);closeSelection()
                scope.launch {try{submit(owned)}catch(e: Exception){showError(e.message ?: e.toString())}}
            }
        })
        wm.addView(root,params(-1,-1))
    }
    private fun closeSelection(){selector?.let {wm.removeView(it)};selector=null;selectedBitmap?.recycle();selectedBitmap=null;handle?.visibility=View.VISIBLE}
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
        clearPage();val bounds=screenSize();region=null;motion.reset();generation++;controller.invalidate()
        layerParams.width=bounds.first;layerParams.height=bounds.second;pageView?.let {wm.updateViewLayout(it,layerParams)}
        if(Build.VERSION.SDK_INT<34)resize(bounds.first,bounds.second)
        closeSelection();handleParams.x=dp(8);handleParams.y=dp(70);handle?.let {wm.updateViewLayout(it,handleParams)}
        panelParams.width=minOf(bounds.first-dp(16),dp(440));panelParams.height=minOf((bounds.second*.6).toInt(),dp(560));panel?.let {wm.updateViewLayout(it,panelParams);it.visibility=View.GONE}
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
        fun recover(context: android.content.Context) {
            context.startService(Intent(context, ReadingOverlayService::class.java).setAction("recover"))
        }

        private const val CHANNEL="reading-overlay"
        private const val NOTIFICATION=912
    }
}
