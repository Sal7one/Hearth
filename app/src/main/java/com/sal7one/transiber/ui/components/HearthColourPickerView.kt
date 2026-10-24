package com.sal7one.transiber.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.uiText
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Opaque colour editing shared by Compose and service-owned Android windows. */
internal class HearthColourPickerView(
    context: Context,
    initialColour: Int,
    private val onPreview: (Int) -> Unit = {},
    private val onCommit: (Int) -> Unit,
) : LinearLayout(context) {
    private val text = context.uiText()
    private val hsv = FloatArray(3)
    private var colour = opaque(initialColour)
    private var ink = Color.WHITE
    private var surface = Color.rgb(25, 30, 38)
    private var accent = Color.rgb(55, 212, 203)
    private var syncingHex = false
    private var hexDirty = false
    private val previewRow: LinearLayout
    private val sample: View
    private val value: TextView
    private val hexLabel: TextView
    private val hexRow: LinearLayout
    private val hex: EditText
    private val apply: Button
    private val plane: ColourPlane
    private val hue: HueStrip

    init {
        orientation = VERTICAL
        Color.colorToHSV(colour, hsv)
        plane = ColourPlane(context).apply { tag = "hearth_colour_sv" }
        hue = HueStrip(context).apply { tag = "hearth_colour_hue" }
        addView(plane, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(hue, LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        previewRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        sample = View(context).apply { tag = "hearth_colour_sample"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        value = TextView(context).apply { textSize = 14f }
        previewRow.addView(sample, LayoutParams(dp(40), dp(40)))
        previewRow.addView(value, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        addView(previewRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        hexLabel = TextView(context).apply { text = this@HearthColourPickerView.text(R.string.colour_picker_hex); textSize = 13f }
        addView(hexLabel, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        hexRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        hex = EditText(context).apply {
            tag = "hearth_colour_hex"
            id = View.generateViewId()
            contentDescription = this@HearthColourPickerView.text(R.string.colour_picker_hex)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(7))
            minHeight = dp(48)
            textSize = 16f
            layoutDirection = LAYOUT_DIRECTION_LTR
            textDirection = TEXT_DIRECTION_LTR
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!syncingHex) { hexDirty = true; error = null }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    applyHex(); true
                } else false
            }
        }
        hexLabel.labelFor = hex.id
        apply = Button(context).apply {
            tag = "hearth_colour_apply"
            text = this@HearthColourPickerView.text(R.string.colour_picker_apply)
            minHeight = dp(48)
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { applyHex() }
        }
        hexRow.addView(hex, LayoutParams(0, WRAP_CONTENT, 1f))
        hexRow.addView(apply, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(hexRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        renderColour()
        setPalette(ink, surface, accent)
    }

    /** External renders must not reset a drag's remembered hue or text the user is editing. */
    fun setColour(argb: Int) {
        val next = opaque(argb)
        if (next == colour) return
        // Service progress can render its last saved colour during a live preview.
        // Keep the active pointer/key edit authoritative until it is finished.
        if (plane.isInteracting || hue.isInteracting) return
        plane.cancelInteraction(notify = false)
        hue.cancelInteraction(notify = false)
        colour = next
        Color.colorToHSV(colour, hsv)
        renderColour()
    }

    fun setHexEnabled(enabled: Boolean) {
        hexLabel.visibility = if (enabled) VISIBLE else GONE
        hexRow.visibility = if (enabled) VISIBLE else GONE
        if (!enabled) hex.clearFocus()
    }

    fun setPalette(ink: Int, surface: Int, accent: Int) {
        this.ink = opaque(ink); this.surface = opaque(surface); this.accent = opaque(accent)
        value.setTextColor(this.ink)
        hexLabel.setTextColor(this.ink)
        hex.setTextColor(this.ink)
        hex.setHintTextColor(this.ink)
        hex.backgroundTintList = ColorStateList.valueOf(this.accent)
        apply.setTextColor(if (Color.luminance(this.accent) > .45) Color.BLACK else Color.WHITE)
        apply.backgroundTintList = ColorStateList.valueOf(this.accent)
        previewRow.background = rounded(this.surface)
        plane.invalidate(); hue.invalidate()
    }

    private fun applyHex() {
        val digits = hex.text.toString().trim().removePrefix("#")
        val parsed = if (digits.length == 6 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) digits.toIntOrNull(16) else null
        if (parsed == null) {
            hex.error = text(R.string.colour_picker_invalid_hex)
            return
        }
        plane.cancelInteraction(notify = false); hue.cancelInteraction(notify = false)
        colour = opaque(parsed)
        Color.colorToHSV(colour, hsv)
        hexDirty = false
        hex.error = null
        renderColour()
        onPreview(colour)
        onCommit(colour)
    }

    private fun renderColour() {
        sample.background = rounded(colour).apply { setStroke(dp(1), ink) }
        value.text = text(R.string.colour_picker_selected, hexValue(colour))
        if (!hexDirty) {
            syncingHex = true
            try { hex.setText(hexValue(colour)) } finally { syncingHex = false }
        }
        plane.rebuildHue()
        plane.invalidate(); hue.invalidate()
    }

    private fun preview() {
        colour = Color.HSVToColor(hsv)
        renderColour()
        onPreview(colour)
    }

    private fun commit(view: View) {
        hexDirty = false
        renderColour()
        onCommit(colour)
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    private fun rounded(colour: Int) = GradientDrawable().apply { setColor(colour); cornerRadius = dp(8).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** Owns the pointer only while dragging; cancellation restores preview without a write. */
    private abstract inner class ColourControl(context: Context) : View(context) {
        private var pointer = MotionEvent.INVALID_POINTER_ID
        private val beforeDrag = FloatArray(3)
        private var keyboardEditing = false
        val isInteracting: Boolean get() = pointer != MotionEvent.INVALID_POINTER_ID || keyboardEditing
        protected val bounds = RectF()
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outerThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.BLACK }
        private val innerThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.WHITE }

        init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; minimumHeight = dp(48) }

        protected fun thumb(canvas: Canvas, x: Float, y: Float) {
            canvas.drawCircle(x, y, dp(7).toFloat(), outerThumb)
            canvas.drawCircle(x, y, dp(7).toFloat(), innerThumb)
            if (isFocused) {
                paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = accent
                canvas.drawRoundRect(dp(1).toFloat(), dp(1).toFloat(), width - dp(1).toFloat(), height - dp(1).toFloat(), dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.style = Paint.Style.FILL
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    hsv.copyInto(beforeDrag)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    isPressed = true
                    select(event.x, event.y)
                    preview()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (index < 0) return false
                    select(event.getX(index), event.getY(index)); preview(); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (pointer == MotionEvent.INVALID_POINTER_ID) return false
                    val index = event.findPointerIndex(pointer)
                    if (index >= 0) { select(event.getX(index), event.getY(index)); preview() }
                    endDrag()
                    commit(this)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { cancelInteraction(); return true }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == pointer) cancelInteraction()
                    return true
                }
            }
            return pointer != MotionEvent.INVALID_POINTER_ID
        }

        fun cancelInteraction(notify: Boolean = true) {
            if (pointer != MotionEvent.INVALID_POINTER_ID) {
                beforeDrag.copyInto(hsv)
                endDrag()
                if (notify) preview() else {
                    colour = Color.HSVToColor(hsv)
                    renderColour()
                }
            }
            keyboardEditing = false
        }

        private fun endDrag() { pointer = MotionEvent.INVALID_POINTER_ID; isPressed = false; parent?.requestDisallowInterceptTouchEvent(false) }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow() }
        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            // Collapsed colour editors stay attached to their service window.
            // Hiding one must abandon its pointer just like an ACTION_CANCEL.
            if (!isVisible) cancelInteraction()
        }
        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (!gainFocus && keyboardEditing) { keyboardEditing = false; commit(this) }
            invalidate()
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (isNextControlKey(keyCode)) return true
            if (adjustKey(keyCode)) { keyboardEditing = true; preview(); return true }
            return super.onKeyDown(keyCode, event)
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (isNextControlKey(keyCode)) {
                if (!event.isCanceled) focusSearch(FOCUS_FORWARD)?.requestFocus()
                return true
            }
            if (isAdjustmentKey(keyCode) && keyboardEditing) {
                keyboardEditing = false
                if (!event.isCanceled) commit(this)
                return true
            }
            return super.onKeyUp(keyCode, event)
        }

        private fun isNextControlKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        protected abstract fun select(x: Float, y: Float)
        protected abstract fun adjustKey(key: Int): Boolean
        protected abstract fun isAdjustmentKey(key: Int): Boolean
    }

    private inner class ColourPlane(context: Context) : ColourControl(context) {
        private var cachedHue = Float.NaN
        private var saturationGradient: Shader? = null
        private var brightnessGradient: Shader? = null
        private val hueColour = floatArrayOf(0f, 1f, 1f)

        init { contentDescription = text(R.string.colour_picker_saturation_brightness) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = resolveSize(dp(200), widthMeasureSpec)
            setMeasuredDimension(width, resolveSize(min(width, dp(200)), heightMeasureSpec))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val side = (min(w, h) - dp(16)).coerceAtLeast(1).toFloat()
            bounds.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            brightnessGradient = LinearGradient(0f, bounds.top, 0f, bounds.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            cachedHue = Float.NaN
            rebuildHue()
        }

        fun rebuildHue() {
            if (cachedHue == hsv[0] || bounds.isEmpty) return
            cachedHue = hsv[0]; hueColour[0] = hsv[0]
            saturationGradient = LinearGradient(bounds.left, 0f, bounds.right, 0f, Color.WHITE, Color.HSVToColor(hueColour), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = saturationGradient
            canvas.drawRoundRect(bounds, dp(6).toFloat(), dp(6).toFloat(), paint)
            paint.shader = brightnessGradient
            canvas.drawRoundRect(bounds, dp(6).toFloat(), dp(6).toFloat(), paint)
            paint.shader = null
            thumb(canvas, bounds.left + hsv[1] * bounds.width(), bounds.top + (1 - hsv[2]) * bounds.height())
        }

        override fun select(x: Float, y: Float) {
            hsv[1] = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            hsv[2] = (1 - (y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }

        override fun isAdjustmentKey(key: Int) = key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
        override fun adjustKey(key: Int): Boolean {
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> hsv[1] = (hsv[1] - .02f).coerceAtLeast(0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> hsv[1] = (hsv[1] + .02f).coerceAtMost(1f)
                KeyEvent.KEYCODE_DPAD_UP -> hsv[2] = (hsv[2] + .02f).coerceAtMost(1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> hsv[2] = (hsv[2] - .02f).coerceAtLeast(0f)
                else -> return false
            }
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.SeekBar"
            info.isScrollable = true
            info.contentDescription = text(R.string.colour_picker_sv_value, (hsv[1] * 100).roundToInt(), (hsv[2] * 100).roundToInt())
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_SATURATION_UP, text(R.string.colour_picker_more_saturation)))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_SATURATION_DOWN, text(R.string.colour_picker_less_saturation)))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_BRIGHTNESS_UP, text(R.string.colour_picker_more_brightness)))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_BRIGHTNESS_DOWN, text(R.string.colour_picker_less_brightness)))
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val key = when (action) {
                ACTION_SATURATION_UP -> KeyEvent.KEYCODE_DPAD_RIGHT
                ACTION_SATURATION_DOWN -> KeyEvent.KEYCODE_DPAD_LEFT
                ACTION_BRIGHTNESS_UP, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> KeyEvent.KEYCODE_DPAD_UP
                ACTION_BRIGHTNESS_DOWN, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> return super.performAccessibilityAction(action, arguments)
            }
            adjustKey(key); preview(); commit(this); return true
        }
    }

    private inner class HueStrip(context: Context) : ColourControl(context) {
        private var gradient: Shader? = null
        init { contentDescription = text(R.string.colour_picker_hue) }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            bounds.set(dp(8).toFloat(), (h - dp(24)) / 2f, (w - dp(8)).coerceAtLeast(dp(8) + 1).toFloat(), (h + dp(24)) / 2f)
            gradient = LinearGradient(bounds.left, 0f, bounds.right, 0f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = gradient
            canvas.drawRoundRect(bounds, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.shader = null
            thumb(canvas, bounds.left + hsv[0] / 360 * bounds.width(), bounds.centerY())
        }

        override fun select(x: Float, y: Float) { hsv[0] = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f) * 360f }
        override fun isAdjustmentKey(key: Int) = key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
        override fun adjustKey(key: Int): Boolean {
            hsv[0] = when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> (hsv[0] - 3f).coerceAtLeast(0f)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> (hsv[0] + 3f).coerceAtMost(360f)
                else -> return false
            }
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.SeekBar"
            info.isScrollable = true
            info.contentDescription = text(R.string.colour_picker_hue_value, hsv[0].roundToInt())
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 360f, hsv[0])
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> adjustKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> adjustKey(KeyEvent.KEYCODE_DPAD_LEFT)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                    if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) return false
                    val requested = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                    if (!requested.isFinite()) return false
                    hsv[0] = requested.coerceIn(0f, 360f)
                }
                else -> return super.performAccessibilityAction(action, arguments)
            }
            preview(); commit(this); return true
        }
    }

    companion object {
        internal const val ACTION_SATURATION_UP = 0x01010001
        internal const val ACTION_SATURATION_DOWN = 0x01010002
        internal const val ACTION_BRIGHTNESS_UP = 0x01010003
        internal const val ACTION_BRIGHTNESS_DOWN = 0x01010004
        private fun opaque(colour: Int) = colour or Color.BLACK
        private fun hexValue(colour: Int) = String.format(Locale.ROOT, "#%06X", colour and 0xffffff)
    }
}
