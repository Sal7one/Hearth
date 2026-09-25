package com.sal7one.transiber.sign

import com.sal7one.common_jni.sign.SignHands
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.uiText as localizedUiText

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Foreground service (camera type) that owns one live fingerspelling session:
 * front-camera CameraX ImageAnalysis → YUV→RGBA into a reused direct buffer
 * (rotation-corrected) → [SignEngineController] → this floating bubble.
 *
 * The bubble uses classic Views, not Compose: per-frame updates are plain
 * TextView writes (allocation-free), no Compose window host is needed inside
 * a camera service, and the draggable FLAG_NOT_FOCUSABLE window follows the
 * proven ReadingOverlayService pattern. The bubble survives the app going
 * to the background — that is the feature.
 */
class SignOverlayService : Service() {
    private val uiText get() = this.localizedUiText()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { getSharedPreferences("sign-overlay", 0) }

    private lateinit var engine: SignEngineController
    private var active = false
        set(value) { field = value; runningState.value = value }
    private var failure: String? = null
    private var bubbleVisible = true
    private var setupSuppressed = false
    private var front = true

    // Camera plumbing.
    private val cameraClosed = AtomicBoolean(true)
    private var cameraHost: CameraHost? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzerExecutor = Executors.newSingleThreadExecutor()
    private var rgba: ByteBuffer? = null
    private var rgbaWidth = 0
    private var rgbaHeight = 0

    // Bubble views; created once, updated in place.
    private var bubble: LinearLayout? = null
    private var bubbleParams = WindowManager.LayoutParams()
    private var letterView: TextView? = null
    private var buildingView: TextView? = null
    private var historyView: TextView? = null
    private var handView: TextView? = null
    private var fpsView: TextView? = null
    private var pauseButton: Button? = null

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) Color.WHITE else Color.rgb(25, 30, 35)
    private val paper get() = if (dark) Color.rgb(28, 32, 38) else Color.rgb(250, 250, 250)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        engine = SignEngineController(this)
        scope.launch {
            com.sal7one.transiber.shortcuts.OverlaySetupVisibility.active.collect { suppressForSetup(it) }
        }
        scope.launch {
            engine.state.collect { value ->
                renderState(value)
                if (active && value.lastError != null && failure == null) fail(value.lastError!!)
            }
        }
        scope.launch {
            com.sal7one.transiber.i18n.AppLocale.revision.collect { if (active) updateNotification() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSession(); return START_NOT_STICKY }
            ACTION_PAUSE -> if (active) togglePause() else stopSelf(startId)
            ACTION_VISIBILITY -> if (active) toggleBubble() else stopSelf(startId)
            ACTION_CENTER -> if (active) { bubbleVisible = true; centerBubble() } else stopSelf(startId)
            ACTION_START -> {
                if (active) { centerBubble(); return START_NOT_STICKY }
                // Failures before the foreground promotion cannot reach the
                // overlay or notification yet; surface them honestly via toast.
                try {
                    check(checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        "Sign camera: CAMERA permission is missing"
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                active = true
                if (!startForegroundCamera()) return START_NOT_STICKY
                createWindows()
                engine.start()
                bindCamera()
            }
            else -> if (!active) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCamera(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        true
    } catch (e: Exception) {
        val message = "Foreground service: ${e.message ?: e.javaClass.simpleName}"
        fail(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        active = false
        stopSelf()
        false
    }

    /** A service has no lifecycle; CameraX borrows this hand-rolled owner (OverlayHost pattern). */
    private class CameraHost : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private fun bindCamera() {
        cameraClosed.set(false)
        val host = CameraHost()
        cameraHost = host
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (cameraClosed.get()) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                useCase.setAnalyzer(analyzerExecutor, ::analyzeFrame)
                analysis = useCase
                host.start()
                cameraProvider.bindToLifecycle(host, cameraSelector(), useCase)
            } catch (e: Exception) {
                fail("Camera: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraSelector() =
        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun switchCamera() {
        front = !front
        prefs.edit().putBoolean("front", front).apply()
        if (!active) return
        // Keep the sentence; only the sensor changes. The stillness gate in
        // SignSequenceBuffer drops the one transition frame that may differ.
        val useCase = analysis
        analysis = null
        val host = cameraHost
        cameraHost = null
        runCatching { provider?.unbind(useCase) }
        runCatching { host?.stop() }
        if (cameraClosed.get()) return
        bindCamera()
    }

    /**
     * CameraX analyzer: YUV_420_888 → rotation-corrected RGBA in the reused
     * direct buffer, then one synchronous inference. The proxy is closed
     * unconditionally; frames the engine cannot consume are dropped before
     * conversion via [SignEngineController.readyForFrame].
     */
    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (cameraClosed.get() || !engine.readyForFrame()) return
            val buffer = convertToRgba(image) ?: return
            engine.offerFrame(buffer, rgbaWidth, rgbaHeight, image.imageInfo.timestamp / 1_000_000L)
        } catch (e: Exception) {
            if (!cameraClosed.get()) handler.post { if (!cameraClosed.get()) fail("Camera frame: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            image.close()
        }
    }

    /**
     * Fuses the YUV_420_888 planes with the integer transform from the old
     * Hearth CameraDetectionScreen.imageProxyToArgb, writing RGBA bytes into
     * the reused direct buffer rotated by imageInfo.rotationDegrees so the
     * palm detector sees upright hands. Sensor frames are unmirrored already;
     * display mirroring is a preview concern only.
     */
    private fun convertToRgba(image: ImageProxy): ByteBuffer? {
        check(image.format == android.graphics.ImageFormat.YUV_420_888 && image.planes.size >= 3) {
            "Unsupported camera format: ${image.format}"
        }
        val rotation = (image.imageInfo.rotationDegrees / 90 * 90).let { if (it < 0) it + 360 else it }
        val width = image.width
        val height = image.height
        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        if (rgba == null || rgbaWidth != outWidth || rgbaHeight != outHeight) {
            rgba = SignHands.frameBuffer(outWidth, outHeight)
            rgbaWidth = outWidth
            rgbaHeight = outHeight
        }
        val output = rgba!!
        output.rewind()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer; yBuffer.rewind()
        val uBuffer = uPlane.buffer; uBuffer.rewind()
        val vBuffer = vPlane.buffer; vBuffer.rewind()
        val yPixelStride = yPlane.pixelStride; val yRowStride = yPlane.rowStride
        val uPixelStride = uPlane.pixelStride; val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride; val vRowStride = vPlane.rowStride

        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uRow = (row shr 1) * uRowStride
            val vRow = (row shr 1) * vRowStride
            for (col in 0 until width) {
                val y = (yBuffer.get(yRow + col * yPixelStride).toInt() and 0xFF) - 16
                val u = (uBuffer.get(uRow + (col shr 1) * uPixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vRow + (col shr 1) * vPixelStride).toInt() and 0xFF) - 128
                val r = ((1192 * y + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((1192 * y - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((1192 * y + 2066 * u) shr 10).coerceIn(0, 255)
                val index = when (rotation) {
                    90 -> (height - 1 - row) * outWidth + col
                    180 -> (height - 1 - row) * outWidth + (width - 1 - col)
                    270 -> row * outWidth + (width - 1 - col)
                    else -> row * outWidth + col
                } * 4
                output.put(index, r.toByte())
                output.put(index + 1, g.toByte())
                output.put(index + 2, b.toByte())
                output.put(index + 3, 0xFF.toByte())
            }
        }
        return output
    }

    private fun fail(message: String) {
        if (!active) return
        failure = message
        releaseCamera()
        engine.stop()
        bubbleVisible = true
        showBubble()
        centerBubble()
        updateNotification()
    }

    private fun stopSession() {
        active = false
        releaseCamera()
        engine.stop()
        hideWindows()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCamera() {
        if (!cameraClosed.compareAndSet(false, true)) return
        val useCase = analysis
        analysis = null
        val host = cameraHost
        cameraHost = null
        handler.post {
            runCatching { useCase?.clearAnalyzer() }
            runCatching { useCase?.let { provider?.unbind(it) } }
            runCatching { provider?.unbindAll() }
            host?.stop()
            provider = null
        }
    }

    private fun togglePause() {
        if (engine.state.value.paused) engine.resume() else engine.pause()
        updateNotification()
    }

    private fun toggleBubble() {
        bubbleVisible = !bubbleVisible
        if (bubbleVisible) showBubble() else bubble?.visibility = View.INVISIBLE
        updateNotification()
    }

    private fun centerBubble() {
        bubbleVisible = true
        showBubble()
        val bounds = screenSize()
        bubbleParams.x = ((bounds.first - (bubble?.width ?: dp(232))) / 2).coerceIn(0, maxOf(0, bounds.first))
        bubbleParams.y = dp(64)
        bubble?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
    }

    private fun showBubble() {
        bubble?.visibility = if (setupSuppressed) View.INVISIBLE else View.VISIBLE
    }

    private fun suppressForSetup(suppressed: Boolean) {
        setupSuppressed = suppressed
        bubble?.visibility = if (suppressed || !bubbleVisible) View.INVISIBLE else View.VISIBLE
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun column() = LinearLayout(this).apply {
        layoutDirection = if (uiText.locale.language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(paper); cornerRadius = dp(16).toFloat() }
    }

    private fun text(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(44); textSize = 13f
        setTextColor(ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(60, 68, 76) else Color.rgb(224, 232, 237))
        setOnClickListener { action() }
    }

    private fun createWindows() {
        if (bubble != null) return
        front = prefs.getBoolean("front", true)
        val size = screenSize()
        bubbleParams = overlayParams(dp(232), -2).apply {
            x = prefs.getInt("x", dp(8)).coerceIn(0, maxOf(0, size.first - dp(232)))
            y = prefs.getInt("y", dp(96)).coerceIn(dp(24), maxOf(dp(24), size.second - dp(160)))
        }
        bubble = column().also { root ->
            val grip = text(uiText(UiR.string.service_drag_79d0e), 12f)
            grip.contentDescription = uiText(UiR.string.service_drag_the_grip_to_move_the_bubble_32952)
            var sx = 0f; var sy = 0f; var x = 0; var y = 0
            grip.setOnTouchListener { view, event -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.rawX; sy = event.rawY; x = bubbleParams.x; y = bubbleParams.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = screenSize()
                    bubbleParams.x = (x + event.rawX - sx).toInt().coerceIn(0, maxOf(0, bounds.first - root.width))
                    bubbleParams.y = (y + event.rawY - sy).toInt().coerceIn(dp(24), maxOf(dp(24), bounds.second - root.height - dp(24)))
                    wm.updateViewLayout(root, bubbleParams); true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply()
                    if (abs(event.rawX - sx) + abs(event.rawY - sy) < 8) view.performClick()
                    true
                }
                else -> false
            } }
            root.addView(grip)
            letterView = text("—", 52f).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
            letterView!!.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            root.addView(letterView)
            buildingView = text("", 20f)
            root.addView(buildingView)
            historyView = text("", 13f, if (dark) Color.rgb(170, 178, 189) else Color.rgb(90, 98, 110))
            root.addView(historyView)
            val statusRow = LinearLayout(this)
            handView = text(uiText(UiR.string.service_no_hand_a1f1f), 12f)
            fpsView = text("", 12f, if (dark) Color.rgb(170, 178, 189) else Color.rgb(90, 98, 110))
            statusRow.addView(handView, LinearLayout.LayoutParams(0, -2, 1f))
            statusRow.addView(fpsView, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(statusRow)
            val controls = LinearLayout(this)
            pauseButton = button(uiText(UiR.string.service_pause_78196)) { togglePause() }
            controls.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button(uiText(UiR.string.service_clear_719ea)) { engine.clearText() }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button(uiText(UiR.string.service_switch_3e44c)) { switchCamera() }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(controls)
            root.addView(button(uiText(UiR.string.service_stop_9e253)) { stopSession() })
            wm.addView(root, bubbleParams)
        }
    }

    private fun hideWindows() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        letterView = null; buildingView = null; historyView = null
        handView = null; fpsView = null; pauseButton = null
    }

    /** Main-thread render of engine state; views are updated in place and only on change. */
    private fun renderState(value: SignUiState) {
        if (bubble == null) return
        val paused = value.paused
        pauseButton?.text = if (paused) uiText(UiR.string.service_resume_b3bd0) else uiText(UiR.string.service_pause_78196)
        letterView?.text = when {
            failure != null -> failure!!.take(160)
            paused -> uiText(UiR.string.service_paused_c7dfb)
            value.loading -> uiText(UiR.string.service_loading_33ce4)
            else -> value.currentLetter ?: "—"
        }
        buildingView?.text = value.buildingText.ifBlank {
            if (failure == null && !paused) uiText(UiR.string.service_show_a_letter_to_the_camera_4a87a) else ""
        }
        // Last ~2 lines of rolled-out history only; the bubble stays compact.
        historyView?.text = value.history.takeLast(72).let { rolling -> if (rolling.isBlank()) "" else "…$rolling" }
        handView?.text = if (value.handPresent) uiText(UiR.string.service_hand_detected_6868e) else uiText(UiR.string.service_no_hand_a1f1f)
        handView?.setTextColor(if (value.handPresent) Color.rgb(0, 150, 110) else (if (dark) Color.rgb(170, 178, 189) else Color.rgb(90, 98, 110)))
        val fps = value.fps
        fpsView?.text = if (fps > 0f && !paused) uiText(UiR.string.service_1_s_fps_47f21, "%.1f".format(fps)) else ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val bounds = screenSize()
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxOf(0, bounds.first - dp(232)))
        bubbleParams.y = bubbleParams.y.coerceIn(dp(24), maxOf(dp(24), bounds.second - dp(160)))
        bubble?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
        if (active) updateNotification()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, uiText(UiR.string.service_sign_language_7b123), NotificationManager.IMPORTANCE_LOW))
        val paused = engine.state.value.paused
        fun command(id: Int, action: String) = PendingIntent.getService(this, id,
            Intent(this, SignOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(when {
                failure != null -> uiText(UiR.string.service_sign_language_error_94056)
                paused -> uiText(UiR.string.service_sign_language_paused_bb251)
                else -> uiText(UiR.string.service_sign_language_7b123)
            })
            .setContentText(failure ?: if (paused) uiText(UiR.string.service_paused_no_camera_frames_reach_the_hand_models_6fc56)
                else uiText(UiR.string.service_fingerspelling_from_the_camera_frames_stay_local_7e343))
            .setStyle(NotificationCompat.BigTextStyle().bigText(failure
                ?: uiText(UiR.string.service_tap_this_notification_to_bring_back_the_sign_bubble_0a8fb)))
            .setContentIntent(command(2, ACTION_CENTER)).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (failure == null) addAction(0, if (paused) uiText(UiR.string.service_resume_b3bd0) else uiText(UiR.string.service_pause_78196), command(1, ACTION_PAUSE)) }
            .apply {
                if (bubbleVisible) addAction(0, uiText(UiR.string.service_hide_bubble_658d0), command(4, ACTION_VISIBILITY))
                else addAction(0, uiText(UiR.string.service_show_bubble_9a42e), command(4, ACTION_VISIBILITY))
            }
            .addAction(0, uiText(UiR.string.service_stop_9e253), command(3, ACTION_STOP))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    override fun onDestroy() {
        active = false
        releaseCamera()
        analyzerExecutor.shutdown()
        hideWindows()
        engine.close()
        // The controller releases native handles and its LocalWorkGate lease
        // in a NonCancellable finally; wait for that before the process lets
        // another workload acquire the gate.
        CoroutineScope(Dispatchers.IO).launch { engine.awaitReleased() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val runningState = MutableStateFlow(false)
        val running: StateFlow<Boolean> = runningState
        private const val CHANNEL_ID = "sign_overlay"
        private const val NOTIFICATION_ID = 414
        private const val ACTION_START = "com.sal7one.transiber.sign.START"
        private const val ACTION_STOP = "com.sal7one.transiber.sign.STOP"
        private const val ACTION_PAUSE = "com.sal7one.transiber.sign.PAUSE"
        private const val ACTION_VISIBILITY = "com.sal7one.transiber.sign.VISIBILITY"
        private const val ACTION_CENTER = "com.sal7one.transiber.sign.CENTER"
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, SignOverlayService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, SignOverlayService::class.java).setAction(ACTION_STOP))
        }
        fun show(context: Context) {
            context.startService(Intent(context, SignOverlayService::class.java).setAction(ACTION_CENTER))
        }
    }
}
