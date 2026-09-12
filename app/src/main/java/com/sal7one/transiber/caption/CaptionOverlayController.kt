package com.sal7one.transiber.caption

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Main-thread window owner. Capture and engine lifetimes belong to the service. */
class CaptionOverlayController(
    private val context: Context,
    val engineController: CaptionEngineController,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _config = MutableStateFlow(CaptionOverlayConfig())
    val config: StateFlow<CaptionOverlayConfig> = _config.asStateFlow()
    private val heightDp = MutableStateFlow(200f)
    private var view: ComposeView? = null
    private var host: OverlayHost? = null
    private var hidden = false
    private var destroyed = false
    private var sessionSource: CaptionSource? = null

    fun show(sourceForSession: CaptionSource? = null) {
        scope.launch {
            if (destroyed || view != null) return@launch
            sessionSource = sourceForSession
            _config.value = CaptionConfigStore.config(context).first().copy(
                source = sourceForSession ?: engineController.currentConfig.source,
                paused = engineController.currentConfig.paused,
            ).withUiClamp()
            val owner = OverlayHost()
            owner.start()
            host = owner
            val created = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val maxHeight by heightDp.collectAsState()
                    CaptionOverlayWindow(
                        config, engineController.state, ::updateConfig,
                        onDrag = ::move, onDragFinished = ::persistCurrent,
                        onClose = ::stopSession, onClear = engineController::clearTranscript,
                        availableHeightDp = maxHeight,
                    )
                }
            }
            view = created
            try {
                wm.addView(created, layoutParams())
                created.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyWindowChanges() }

            } catch (e: Exception) {
                engineController.reportError("Overlay: "+(e.message ?: e.javaClass.simpleName))
                hide()
            }
        }
    }

    fun hide() {
        scope.launch {
            view?.let { runCatching { wm.removeViewImmediate(it) }; it.disposeComposition() }
            view = null
            host?.stop()
            host = null
        }
    }

    fun destroy() {
        destroyed = true
        hide()
        scope.cancel()
    }

    fun stopSession() = CaptionCaptureService.stop(context)

    fun updateConfig(transform: (CaptionOverlayConfig) -> CaptionOverlayConfig) {
        scope.launch {
            _config.value = transform(_config.value).withUiClamp()
            applyWindowChanges()
            engineController.updateRuntimeConfig(_config.value)
            CaptionConfigStore.update(context) { _config.value }
        }
    }

    private fun move(dx: Int, dy: Int) {
        val c = _config.value
        _config.value = c.copy(
            xOffsetPx = c.xOffsetPx + dx,
            yOffsetPx = c.yOffsetPx + if (c.anchor == CaptionAnchor.BOTTOM) -dy else dy,
        )
        applyWindowChanges(normalize = true)
    }

    fun persistCurrent() { scope.launch { CaptionConfigStore.update(context) { _config.value } } }

    fun centerOverlay() {
        hidden = false
        view?.visibility = android.view.View.VISIBLE
        updateConfig { it.copy(anchor = CaptionAnchor.CENTER, xOffsetPx = 0, yOffsetPx = 0, tapThrough = false, showSettings = false, languagePicker = null) }
    }

    fun toggleVisibility() {
        hidden = !hidden
        view?.visibility = if (hidden) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    fun refreshBounds() { scope.launch { applyWindowChanges(normalize = true) } }

    private fun viewport(): OverlayViewport {
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val b = m.bounds
            val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return OverlayViewport(i.left + margin, i.top + margin, b.width() - i.right - margin, b.height() - i.bottom - margin)
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        return OverlayViewport(margin, margin, metrics.widthPixels - margin, metrics.heightPixels - margin)
    }

    private fun layoutParams(normalize: Boolean = false): WindowManager.LayoutParams {
        val c = _config.value
        val vp = viewport()
        val density = context.resources.displayMetrics.density
        val maxHeight = overlayHeightPx(vp.height, density, c)
        heightDp.value = maxHeight / density
        val p = placeOverlay(vp, vp.width * c.widthPercent / 100,
            maxHeight,
            c.anchor, c.xOffsetPx, c.yOffsetPx)
        if (normalize) {
            val origin = placeOverlay(vp, p.width, p.height, c.anchor, 0, 0)
            _config.value = c.copy(xOffsetPx = p.x - origin.x,
                yOffsetPx = if (c.anchor == CaptionAnchor.BOTTOM) origin.y - p.y else p.y - origin.y)
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (c.tapThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(p.width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = p.x
            y = p.y
            // Android 12+ checks WINDOW alpha for pass-through touches, not
            // the Compose background's paint alpha.
            alpha = if (c.tapThrough && Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch.coerceIn(0f, 1f)
            } else 1f
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
    }

    private fun applyWindowChanges(normalize: Boolean = false) {
        val v = view ?: return
        try {
            val next = layoutParams(normalize)
            val old = v.layoutParams as? WindowManager.LayoutParams
            if (old == null || old.x != next.x || old.y != next.y || old.width != next.width || old.flags != next.flags || old.alpha != next.alpha) {
                wm.updateViewLayout(v, next)
            }
        } catch (e: Exception) {
            engineController.reportError("Overlay layout: "+(e.message ?: e.javaClass.simpleName))
        }
    }

    private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
