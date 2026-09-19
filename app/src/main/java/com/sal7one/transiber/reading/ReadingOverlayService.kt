package com.sal7one.transiber.reading

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
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
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val wm by lazy {getSystemService(WindowManager::class.java)}
    private val prefs by lazy {getSharedPreferences("reading-overlay",0)}
    private val camera by lazy {getSharedPreferences("camera-translate",0)}
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
    private var frameWaiter: CancellableContinuation<Bitmap>?=null
    private var handle: LinearLayout?=null
    private var panel: LinearLayout?=null
    private var selector: LinearLayout?=null
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
    private var fingerprint: IntArray?=null
    private var region: RectF?=null
    private val trigger=ReadingTrigger()
    private var readerPackage=""
    private val history=ArrayDeque<Pair<String,String>>()
    private var latest=CameraOcrState()
    private var lastHistory=""
    private var historyIndex=-1
    var paused=false; private set
    val volumeShortcut get()=prefs.getBoolean("volume",false) && readerPackage.isNotBlank() && readerPackage!=packageName && !readerPackage.startsWith("com.android.systemui")
    private val dark get()=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get()=if(dark)Color.WHITE else Color.rgb(25,30,35)
    private val paper get()=if(dark)Color.rgb(28,32,38) else Color.rgb(250,250,250)
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun now()=SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        controller=CameraOcrController(this)
        voice=VoicePlayer(this){_,error->if(error!=null)showError(error)}
    }
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action!=null && projection==null){stopSelf();return START_NOT_STICKY}
        when(intent?.action) {
            "stop" -> {stopSelf();return START_NOT_STICKY}
            "pause" -> {togglePause();return START_NOT_STICKY}
            "translate" -> {requestCapture(false);return START_NOT_STICKY}
            "recover" -> {handleParams.x=dp(8);handleParams.y=dp(100);handle?.let {wm.updateViewLayout(it,handleParams)};return START_NOT_STICKY}
        }
        if(projection!=null)return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Screen reading",NotificationManager.IMPORTANCE_LOW))
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION,notification())
            @Suppress("DEPRECATION") val token=intent?.getParcelableExtra<Intent>("projection") ?: error("Screen capture permission is missing. Start reading again from Camera.")
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,token)
            projection!!.registerCallback(object:MediaProjection.Callback(){
                override fun onStop(){stopSelf()}
                override fun onCapturedContentResize(width: Int,height: Int){resize(width,height)}
            },handler)
            val size=screenSize();resize(size.first,size.second)
            display=projection!!.createVirtualDisplay("Hearth reading",reader!!.width,reader!!.height,resources.configuration.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler)
            profile=OcrCatalog.profile(camera.getString("profile","latin")!!)
            source=camera.getString("source","en")!!;target=camera.getString("target","ar")!!
            trigger.mode=prefs.getString("mode","manual")!!;trigger.settleMs=prefs.getLong("settle",700);trigger.screenDistance=prefs.getFloat("distance",.75f);trigger.scrollBursts=prefs.getInt("bursts",2)
            createWindows();active=this
            scope.launch {
                try {
                    val config=CaptionConfigStore.config(this@ReadingOverlayService).first()
                    if(camera.getBoolean("translate",true) && source!=target) {
                        val provider=if(ByokPolicy.FEATURE_BYOK)ConversationTranslationSettings.provider(camera.getString("provider","local")!!) else null
                        snapshot=if(provider==null)ConversationTranslatorSnapshot(config.localTranslationModelId) else ConversationTranslatorSnapshot(config.localTranslationModelId,ConversationTranslationSettings.connection(this@ReadingOverlayService,provider),checkNotNull(ConversationTranslationSettings.capabilities(this@ReadingOverlayService,provider)){"Check ${provider.label} languages in translation connections first"})
                    }
                    setupReady=true
                    controller.state.collect {value->
                        latest=value
                        if(value.text.isNotBlank()) {original?.text=value.text;if(working)showPanel()}
                        if(value.translation.isNotBlank())translated?.text=value.translation
                        if(value.error!=null)showError(value.error)
                        else status?.text=when {value.loading->"Loading ${profile.label}…";value.processing->"Reading text…";value.translating->"Translating…";value.text.isNotBlank()->"${profile.label} · ${value.ocrMs} ms OCR";else->"Open a reader, then tap Translate or Draw area."}
                        if(working && !value.loading && !value.processing && !value.translating) {
                            working=false
                            handleLabel?.text="Translate"
                            if(value.text.isNotBlank()) {
                                val key=value.text+"\u0000"+value.translation
                                if(key!=lastHistory){historyIndex=-1;lastHistory=key;history.addFirst(value.text to value.translation);while(history.size>20)history.removeLast()}
                                showPanel()
                            } else if(value.error==null) {status?.text="No text found in this area. Draw a smaller region or choose another OCR model.";showPanel()}
                        }
                    }
                }catch(e: CancellationException){throw e}catch(e: Exception){setupError=e.message ?: e.toString();showError(setupError!!)}
            }
            scope.launch {
                while(isActive) {
                    delay(maxOf(900,trigger.settleMs))
                    if(paused || selector!=null || panel?.visibility==View.VISIBLE || captureJob?.isActive==true || trigger.mode=="manual")continue
                    if(profile.engine=="manga" && region==null)continue
                    captureJob=scope.launch { samplePage() }
                }
            }
        }catch(e: Exception) {Toast.makeText(this,e.message ?: e.toString(),Toast.LENGTH_LONG).show();stopSelf()}
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        fun action(id: Int,name: String)=PendingIntent.getService(this,id,Intent(this,ReadingOverlayService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Hearth screen reading")
            .setContentText(if(paused)"Paused · tap Translate to read once" else "Read pages in another app · screenshots stay local")
            .setOngoing(true).setContentIntent(action(44,"recover"))
            .addAction(0,"Translate",action(41,"translate")).addAction(0,if(paused)"Resume" else "Pause",action(42,"pause")).addAction(0,"Stop",action(43,"stop")).build()
    }
    private fun togglePause() {
        paused=!paused;trigger.reset();generation++;working=false;controller.invalidate();voice.stop()
        panel?.visibility=View.GONE;handle?.visibility=View.VISIBLE
        handleLabel?.text=if(paused)"Paused · read" else "Translate"
        pauseButton?.text=if(paused)"Resume auto" else "Pause auto"
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
        frameWaiter?.let {if(it.isActive)it.resumeWithException(IllegalStateException("Screen size changed. Please capture the page again."))};frameWaiter=null
        display?.surface=null;reader?.close();reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        display?.resize(w,h,resources.configuration.densityDpi)
        region=null;fingerprint=null;generation++;trigger.reset()
        if(::controller.isInitialized)controller.invalidate()
    }
    private suspend fun screenshot(): Bitmap {
        handle?.visibility=View.INVISIBLE;panel?.visibility=View.GONE
        try {
            delay(180) // Let SurfaceFlinger remove our controls before attaching the fresh capture surface.
            val input=checkNotNull(reader){"Screen capture has stopped"}
            while(true){val old=input.acquireLatestImage() ?: break;old.close()}
            return withTimeout(5000) {suspendCancellableCoroutine {continuation->
                frameWaiter=continuation
                input.setOnImageAvailableListener({images->
                    val image=images.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if(!continuation.isActive)return@setOnImageAvailableListener
                        val plane=image.planes[0];check(plane.pixelStride==4){"Unsupported screen pixel stride: ${plane.pixelStride}"}
                        val paddedWidth=plane.rowStride/4
                        val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                        if(cropped!==padded)padded.recycle()
                        continuation.resume(cropped)
                    }catch(e: Exception){if(continuation.isActive)continuation.resumeWithException(e)}finally{image.close()}
                },handler)
                display?.surface=input.surface ?: error("Screen capture has stopped")
            }}
        } finally {
            frameWaiter=null;reader?.setOnImageAvailableListener(null,null);display?.surface=null
            if(!stopping)handle?.visibility=View.VISIBLE
        }
    }
    private fun signature(bitmap: Bitmap): IntArray {
        val tiny=Bitmap.createScaledBitmap(bitmap,32,48,false)
        return IntArray(32*48).also {pixels->tiny.getPixels(pixels,0,32,0,0,32,48);pixels.indices.forEach {i->val p=pixels[i];pixels[i]=(Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10};tiny.recycle()}
    }
    private suspend fun samplePage() {
        try {
            val bitmap=screenshot()
            try {
                val next=signature(bitmap)
                if(PageDifference.changed(fingerprint,next)) {
                    fingerprint=next;trigger.movement(now());generation++;working=false;controller.invalidate();original?.text="";translated?.text=""
                }
                if(trigger.ready(now()) && !working) {trigger.accepted(now());submit(bitmap.copy(Bitmap.Config.ARGB_8888,false))}
            }finally{bitmap.recycle()}
        }catch(e: CancellationException){throw e}catch(e: Exception){paused=true;showError(e.message ?: e.toString())}
    }
    fun readerChanged(name: String) {
        if(name==readerPackage)return
        readerPackage=name;fingerprint=null;trigger.reset();generation++;working=false;controller.invalidate();panel?.visibility=View.GONE;handle?.visibility=View.VISIBLE
    }
    fun scroll(delta: Int,name: String) {
        if(paused)return
        if(readerPackage!=name)readerChanged(name)
        trigger.scroll(delta,screenSize().second,now());generation++;working=false;controller.invalidate();panel?.visibility=View.GONE;handle?.visibility=View.VISIBLE
    }
    fun requestCapture(draw: Boolean) {
        if(stopping || projection==null || captureJob?.isActive==true || selector!=null)return
        voice.stop();generation++;controller.invalidate();working=false
        captureJob=scope.launch {
            try {
                val bitmap=screenshot();fingerprint=signature(bitmap);trigger.accepted(now())
                if(draw || (profile.engine=="manga"&&region==null))selectRegion(bitmap) else submit(bitmap)
            }catch(e: CancellationException){throw e}catch(e: Exception){showError(e.message ?: e.toString())}
        }
    }
    private suspend fun submit(bitmap: Bitmap) {
        var input=bitmap
        try {
            check(setupReady){setupError ?: "Reading settings are still loading. Tap Retry in a moment."}
            check(source in profile.languages){"Choose a text language supported by ${profile.label} in Camera settings"}
            region?.let {r->
                val crop=ReadingSelection.crop(bitmap.width,bitmap.height,bitmap.width.toFloat(),bitmap.height.toFloat(),r.left*bitmap.width,r.top*bitmap.height,r.right*bitmap.width,r.bottom*bitmap.height) ?: error("Selected area is too small. Draw it again.")
                input=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width,crop.height)
                if(input!==bitmap)bitmap.recycle()
            }
            val pixels=signature(input)
            check(pixels.any {it>8}){"The captured area is black. Check that the reader is visible; protected pages may block screenshots."}
            original?.text="";translated?.text="";status?.text="Reading ${profile.label}…";handleLabel?.text="Reading…"
            val epoch=generation
            if(latest.closing)controller.awaitStopped()
            if(epoch!=generation){input.recycle();return}
            if(!latest.running)controller.start(profile,source,target,snapshot,false,input)
            else controller.offer(input,true)
            working=true
        }catch(e: Exception){if(!input.isRecycled)input.recycle();throw e}
    }
    private fun params(width: Int,height: Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.LEFT}
    private fun column()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6));background=android.graphics.drawable.GradientDrawable().apply {setColor(paper);cornerRadius=dp(16).toFloat()}}
    private fun text(value: String,size: Float=16f)=TextView(this).apply {text=value;textSize=size;setTextColor(ink);setPadding(dp(4),dp(4),dp(4),dp(4))}
    private fun button(label: String,action: ()->Unit)=Button(this).apply {text=label;isAllCaps=false;minHeight=dp(48);setTextColor(ink);backgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)Color.rgb(60,68,76) else Color.rgb(224,232,237));setOnClickListener{action()}}
    private fun row(parent: LinearLayout,vararg actions: Pair<String,()->Unit>) {
        val row=LinearLayout(this);actions.forEach {(label,action)->row.addView(button(label,action),LinearLayout.LayoutParams(0,-2,1f))};parent.addView(row)
    }
    private fun createWindows() {
        val size=screenSize()
        handleParams=params(dp(156),-2).apply {x=prefs.getInt("x",dp(8)).coerceIn(0,maxOf(0,size.first-dp(156)));y=prefs.getInt("y",dp(100)).coerceIn(dp(24),maxOf(dp(24),size.second-dp(160)))}
        handle=column().also {root->
            val grip=text("Hearth · drag",12f);grip.contentDescription="Move reading handle. Drag to reposition. Double tap to reset position."
            var sx=0f;var sy=0f;var x=0;var y=0
            grip.setOnClickListener {handleParams.x=dp(8);handleParams.y=dp(100);wm.updateViewLayout(root,handleParams)}
            grip.setOnTouchListener {view,event->when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{sx=event.rawX;sy=event.rawY;x=handleParams.x;y=handleParams.y;true}
                MotionEvent.ACTION_MOVE->{val bounds=screenSize();handleParams.x=(x+event.rawX-sx).toInt().coerceIn(0,maxOf(0,bounds.first-root.width));handleParams.y=(y+event.rawY-sy).toInt().coerceIn(dp(24),maxOf(dp(24),bounds.second-root.height-dp(24)));wm.updateViewLayout(root,handleParams);true}
                MotionEvent.ACTION_UP->{prefs.edit().putInt("x",handleParams.x).putInt("y",handleParams.y).apply();if(abs(event.rawX-sx)+abs(event.rawY-sy)<8)view.performClick();true}
                else->false
            }}
            root.addView(grip)
            val controls=LinearLayout(this)
            handleLabel=button("Translate"){requestCapture(false)}.apply {textSize=13f}
            controls.addView(handleLabel,LinearLayout.LayoutParams(0,-2,2f))
            controls.addView(button("⋯"){showPanel()}.apply {contentDescription="Reading controls"},LinearLayout.LayoutParams(0,-2,1f))
            root.addView(controls);wm.addView(root,handleParams)
        }
        panelParams=params(minOf(size.first-dp(16),dp(440)),minOf((size.second*.60).toInt(),dp(560))).apply {x=dp(8);y=dp(60)}
        panel=column().also {root->
            row(root,"Read page" to {root.visibility=View.GONE;handle?.visibility=View.VISIBLE},"Stop" to {stopSelf()})
            status=text("Open a reader, then tap Translate or Draw area.",13f);status!!.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;root.addView(status)
            val scroll=ScrollView(this);val content=column();scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            content.addView(text("Translation",14f));translated=text("",prefs.getFloat("textSize",20f));translated!!.setTextIsSelectable(true);content.addView(translated)
            row(content,"Read aloud" to {speak(false,VoicePlaybackMode.DEFAULT)},"Android voice" to {speak(false,VoicePlaybackMode.SYSTEM)})
            content.addView(text("Original",14f));original=text("");original!!.setTextIsSelectable(true);content.addView(original)
            row(content,"Read original" to {speak(true,VoicePlaybackMode.DEFAULT)},"Stop voice" to {voice.stop()})
            row(content,"Copy" to {getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hearth reading",translated?.text?.takeIf {it.isNotBlank()} ?: original?.text))},"History" to {showHistory()})
            row(content,"Translate" to {requestCapture(false)},"Draw area" to {requestCapture(true)})
            row(content,"Whole page" to {region=null;requestCapture(false)},"Clear" to {clear()})
            pauseButton=button("Pause auto"){togglePause()};content.addView(pauseButton)
            row(content,"Smaller text" to {changeText(-2)},"Larger text" to {changeText(2)})
            content.addView(text("Automatic reading waits while this panel is open. Tap Read page to return to your reader. History lasts for this session (20 pages).",12f))
            row(content,"Setup" to {startActivity(Intent(this,ReadingStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));stopSelf()},"Hearth" to {startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))})
            wm.addView(root,panelParams);root.visibility=View.GONE
        }
    }
    private fun changeText(delta: Int) {val value=(prefs.getFloat("textSize",20f)+delta).coerceIn(14f,34f);prefs.edit().putFloat("textSize",value).apply();translated?.textSize=value}
    private fun speak(originalText: Boolean,mode: VoicePlaybackMode) {
        val text=if(originalText)original?.text.toString() else translated?.text.toString()
        if(text.isNotBlank())voice.speak(text,if(originalText)source else target,mode)
    }
    private fun showHistory() {
        if(history.isEmpty()){status?.text="No completed pages yet";return}
        historyIndex=(historyIndex+1)%history.size
        val entry=history.elementAt(historyIndex)
        original?.text=entry.first;translated?.text=entry.second
        original?.scrollTo(0,0);translated?.scrollTo(0,0)
        status?.text="History ${historyIndex+1}/${history.size} · tap History for the next page"
        showPanel()
    }
    private fun clear() {generation++;controller.invalidate();voice.stop();history.clear();historyIndex=-1;lastHistory="";original?.text="";translated?.text="";working=false;handleLabel?.text="Translate";status?.text="Cleared. Tap Translate for the current page."}
    private fun showPanel() {if(selector==null){panel?.visibility=View.VISIBLE;handle?.visibility=View.GONE}}
    private fun showError(message: String) {status?.text=message;working=false;handleLabel?.text="Retry";showPanel()}
    private fun selectRegion(bitmap: Bitmap) {
        selectedBitmap=bitmap;handle?.visibility=View.GONE;panel?.visibility=View.GONE
        val root=column();selector=root
        val area=SelectionView(bitmap)
        root.addView(text("Draw around a bubble or paragraph",20f))
        root.addView(text("Drag or circle the text. Center and Whole image also work with TalkBack.",13f))
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        row(root,"Center" to {area.chooseCenter()},"Whole image" to {area.whole()})
        row(root,"Cancel" to {closeSelection()},"Read area" to {
            val crop=area.crop()
            if(crop==null){Toast.makeText(this,"Draw an area first, or choose Center",Toast.LENGTH_SHORT).show()}
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
        init {contentDescription="Select a text area by drawing. Center and Whole image buttons are available below."}
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
        val bounds=screenSize();region=null;fingerprint=null;generation++;controller.invalidate()
        if(Build.VERSION.SDK_INT<34)resize(bounds.first,bounds.second)
        closeSelection();handleParams.x=dp(8);handleParams.y=dp(70);handle?.let {wm.updateViewLayout(it,handleParams)}
        panelParams.width=minOf(bounds.first-dp(16),dp(440));panelParams.height=minOf((bounds.second*.6).toInt(),dp(560));panel?.let {wm.updateViewLayout(it,panelParams);it.visibility=View.GONE}
    }
    override fun onDestroy() {
        stopping=true;if(active===this)active=null
        frameWaiter?.cancel();frameWaiter=null;scope.cancel();controller.close();voice.close()
        display?.release();display=null;reader?.close();reader=null;projection?.stop();projection=null
        listOfNotNull(selector,panel,handle).forEach {wm.removeView(it)};selectedBitmap?.recycle();selectedBitmap=null
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        private const val CHANNEL="reading-overlay"
        private const val NOTIFICATION=912
        internal var active: ReadingOverlayService?=null;private set
    }
}
