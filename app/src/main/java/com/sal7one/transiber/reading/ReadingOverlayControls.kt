package com.sal7one.transiber.reading

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.uiText
import kotlin.math.roundToInt

/** Runtime controls share one immutable state with the service; widgets never own reader preferences. */
internal data class ReadingOverlayUiState(
    val paused: Boolean = false,
    val working: Boolean = false,
    val passThrough: Boolean = true,
    val translationsVisible: Boolean = true,
    val showOriginal: Boolean = false,
    val mode: String = "manual",
    val textSize: Float = 20f,
    val settleMs: Long = 900,
    val scanMs: Long = 500,
    val refreshCooldownMs: Long = 4000,
    val changeSensitivity: String = "balanced",
    val compactHandle: Boolean = true,
    val selectedArea: Boolean = false,
    val languagePair: String = "",
    val historyCount: Int = 0,
)

internal data class ReadingOverlayCallbacks(
    val translate: () -> Unit,
    val pause: () -> Unit,
    val passThrough: (Boolean) -> Unit,
    val visibility: (Boolean) -> Unit,
    val showOriginal: (Boolean) -> Unit,
    val selectArea: () -> Unit,
    val wholePage: () -> Unit,
    val clear: () -> Unit,
    val mode: (String) -> Unit,
    val textSize: (Float) -> Unit,
    val settleMs: (Long) -> Unit,
    val scanMs: (Long) -> Unit,
    val refreshCooldownMs: (Long) -> Unit,
    val changeSensitivity: (String) -> Unit,
    val compactHandle: (Boolean) -> Unit,
    val copy: () -> Unit,
    val speak: (original: Boolean, system: Boolean) -> Unit,
    val stopVoice: () -> Unit,
    val history: () -> Unit,
    val openSetup: () -> Unit,
    val openHearth: () -> Unit,
    val stop: () -> Unit,
    val dismiss: () -> Unit,
)

/**
 * A focusable overlay root consumes Back before the application underneath can see it.
 * The WindowManager owner must remove FLAG_NOT_FOCUSABLE while this root is visible.
 * No accessibility service is required. Android 9–12 use key/pre-IME dispatch; Android 13+
 * also registers with this window's dispatcher and releases the registration on hide/detach.
 */
internal open class ReadingBackAwareLayout(context: Context, private val onBack: () -> Unit) : LinearLayout(context) {
    private var registeredDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { onBack() } else null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = consumeBack(event) || super.dispatchKeyEvent(event)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { onBack(); return true }
        return super.onTouchEvent(event)
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        (keyCode == KeyEvent.KEYCODE_BACK && consumeBack(event)) || super.onKeyPreIme(keyCode, event)

    private fun consumeBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBackDispatcher()
        requestFocus()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateBackDispatcher()
        if (isShown) requestFocus()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) registeredDispatcher?.unregisterOnBackInvokedCallback(backCallback!!)
        registeredDispatcher = null
        super.onDetachedFromWindow()
    }

    private fun updateBackDispatcher() {
        if (Build.VERSION.SDK_INT < 33) return
        val wanted = if (isAttachedToWindow && isShown) findOnBackInvokedDispatcher() else null
        if (wanted === registeredDispatcher) return
        registeredDispatcher?.unregisterOnBackInvokedCallback(backCallback!!)
        registeredDispatcher = wanted
        wanted?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback!!)
    }
}

/** A small persistent launcher. Moving it never changes the translation layer's touch policy. */
internal class ReadingOverlayHandle(
    context: Context,
    onTranslate: () -> Unit,
    onOpenControls: () -> Unit,
) : LinearLayout(context) {
    private val palette = ReadingOverlayPalette(context)
    private val text = context.uiText()
    val primaryButton: Button
    val statusText: TextView
    val dragTarget: View
    private val compactTranslate: Button
    private val dragIndicator: TextView

    init {
        orientation = VERTICAL
        layoutDirection = if (text.locale.language == "ar") LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        background = palette.surface(24)
        elevation = palette.dp(8).toFloat()
        setPadding(palette.dp(6), palette.dp(4), palette.dp(6), palette.dp(4))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val grip = FrameLayout(context).apply {
            contentDescription = this@ReadingOverlayHandle.text(R.string.reading_overlay_move)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        grip.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(palette.dp(32), palette.dp(32), Gravity.CENTER))
        dragIndicator = TextView(context).apply {
            text = "⠿"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(palette.muted)
            background = palette.fill(palette.raised, 8)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        grip.addView(dragIndicator, FrameLayout.LayoutParams(palette.dp(16), palette.dp(16), Gravity.BOTTOM or Gravity.END))
        top.addView(grip, LayoutParams(palette.dp(48), palette.dp(48)))
        dragTarget = grip
        statusText = palette.label(text(R.string.reading_overlay_ready), 12f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        top.addView(statusText, LayoutParams(0, WRAP_CONTENT, 1f))
        compactTranslate = palette.button("↻", onTranslate, primary = true).apply {
            contentDescription = this@ReadingOverlayHandle.text(R.string.reading_overlay_translate)
            textSize = 23f
            visibility = GONE
        }
        top.addView(compactTranslate, LayoutParams(palette.dp(48), palette.dp(48)))
        top.addView(palette.button("⋯", onOpenControls).apply {
            textSize = 24f
            contentDescription = this@ReadingOverlayHandle.text(R.string.reading_overlay_open_controls)
        }, LayoutParams(palette.dp(48), palette.dp(48)))
        addView(top, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        primaryButton = palette.button(text(R.string.reading_overlay_translate), onTranslate, primary = true)
        addView(primaryButton, LayoutParams(MATCH_PARENT, palette.dp(48)))
    }

    fun render(state: ReadingOverlayUiState) {
        setPadding(if (state.compactHandle) 0 else palette.dp(6), if (state.compactHandle) 0 else palette.dp(4),
            if (state.compactHandle) 0 else palette.dp(6), if (state.compactHandle) 0 else palette.dp(4))
        dragIndicator.apply {
            text = if (state.paused) "Ⅱ" else "⠿"
            setTextColor(if (state.paused) palette.paused else palette.muted)
        }
        primaryButton.text = text(if (state.working) R.string.reading_overlay_reading else R.string.reading_overlay_translate)
        primaryButton.isEnabled = !state.working
        compactTranslate.isEnabled = !state.working
        primaryButton.visibility = if (state.compactHandle) GONE else VISIBLE
        statusText.visibility = if (state.compactHandle) GONE else VISIBLE
        compactTranslate.visibility = if (state.compactHandle) VISIBLE else GONE
        compactTranslate.text = if (state.working) "…" else "↻"
        statusText.text = text(when {
            state.working -> R.string.reading_overlay_reading
            state.paused -> R.string.reading_overlay_paused
            state.mode == "manual" -> R.string.reading_overlay_manual_status
            else -> R.string.reading_overlay_auto_status
        })
        dragTarget.contentDescription = text(R.string.reading_overlay_move_status, statusText.text)
        compactTranslate.contentDescription = text(R.string.reading_overlay_translate_status, statusText.text)
    }
}

/** A focused, scrollable control sheet. Reopening it restores controls without recapturing the page. */
internal class ReadingOverlayControls(
    context: Context,
    private val callbacks: ReadingOverlayCallbacks,
) : ReadingBackAwareLayout(context, callbacks.dismiss) {
    private val palette = ReadingOverlayPalette(context)
    private val text = context.uiText()
    private var rendering = false
    private var state = ReadingOverlayUiState()
    private val expandedSections = mutableListOf<Pair<Button, LinearLayout>>()
    val statusText: TextView
    val originalText: TextView
    val translatedText: TextView
    private val languageText: TextView
    private val primaryButton: Button
    private val pauseButton: Button
    private val selectedAreaText: TextView
    private val passThrough: Switch
    private val visibilitySwitch: Switch
    private val originalSwitch: Switch
    private val pageMode: Button
    private val manualMode: Button
    private val textSize: SeekBar
    private val textSizeLabel: TextView
    private val settle: SeekBar
    private val settleLabel: TextView
    private val scan: SeekBar
    private val scanLabel: TextView
    private val historyButton: Button
    private val cooldown: SeekBar
    private val cooldownLabel: TextView
    private val sensitivityButtons: List<Pair<String, Button>>
    private val compactSwitch: Switch
    private val controlsPage: LinearLayout
    private val textPage: LinearLayout
    private val controlsTab: Button
    private val textTab: Button
    private val automation: LinearLayout
    private val appearance: LinearLayout
    private val scroll: ScrollView

    init {
        orientation = VERTICAL
        layoutDirection = if (text.locale.language == "ar") LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        background = palette.surface(28)
        clipToOutline = true
        elevation = palette.dp(16).toFloat()
        setPadding(palette.dp(16), palette.dp(12), palette.dp(16), palette.dp(12))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(palette.dp(10), palette.dp(10), palette.dp(10), palette.dp(10))
        }, LayoutParams(palette.dp(44), palette.dp(44)))
        val titles = vertical()
        titles.addView(palette.label(text(R.string.reading_overlay_title), 20f, strong = true))
        languageText = palette.label("", 12f)
        titles.addView(languageText)
        heading.addView(titles, LayoutParams(0, WRAP_CONTENT, 1f))
        heading.addView(palette.button("×", callbacks.dismiss).apply {
            contentDescription = text(R.string.reading_overlay_close)
            textSize = 25f
        }, LayoutParams(palette.dp(48), palette.dp(48)))
        addView(heading, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        statusText = palette.label(text(R.string.reading_overlay_ready), 13f).apply {
            setPadding(palette.dp(12), palette.dp(10), palette.dp(12), palette.dp(10))
            background = palette.fill(palette.raised, 12)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(statusText, spaced(top = 10, bottom = 10))
        val tabs = LinearLayout(context)
        controlsTab = palette.button(text(R.string.reading_overlay_controls), { selectText(false) })
        textTab = palette.button(text(R.string.reading_overlay_text), { selectText(true) })
        tabs.addView(controlsTab, LayoutParams(0, palette.dp(48), 1f))
        tabs.addView(textTab, LayoutParams(0, palette.dp(48), 1f).apply { marginStart = palette.dp(8) })
        addView(tabs, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll = ScrollView(context).apply { isFillViewport = false; isVerticalScrollBarEnabled = true; clipToPadding = false }
        val content = vertical()
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(scroll, LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = palette.dp(12) })
        controlsPage = vertical()
        textPage = vertical()
        content.addView(controlsPage)
        content.addView(textPage)

        primaryButton = palette.button(text(R.string.reading_overlay_translate), callbacks.translate, primary = true)
        pauseButton = palette.button(text(R.string.reading_overlay_pause), callbacks.pause)
        controlsPage.addView(buttonRow(primaryButton, pauseButton), spaced(bottom = 12))
        passThrough = toggle(R.string.reading_overlay_passthrough, R.string.reading_overlay_passthrough_detail, callbacks.passThrough)
        originalSwitch = toggle(R.string.reading_overlay_original, R.string.reading_overlay_original_detail, callbacks.showOriginal)
        controlsPage.addView(sectionLabel(R.string.reading_overlay_area))
        selectedAreaText = palette.label("", 13f)
        controlsPage.addView(selectedAreaText, spaced(bottom = 6))
        controlsPage.addView(buttonRow(palette.button(text(R.string.reading_overlay_select_area), callbacks.selectArea),
            palette.button(text(R.string.reading_overlay_whole_screen), callbacks.wholePage)), spaced(bottom = 12))

        automation = disclosure(controlsPage, R.string.reading_overlay_automation)
        automation.addView(palette.label(text(R.string.reading_overlay_automation_detail), 13f), spaced(bottom = 8))
        pageMode = palette.button(text(R.string.reading_overlay_after_change), { callbacks.mode("page") })
        manualMode = palette.button(text(R.string.reading_overlay_on_tap), { callbacks.mode("manual") })
        automation.addView(buttonRow(pageMode, manualMode), spaced(bottom = 10))
        settleLabel = palette.label("", 13f)
        automation.addView(settleLabel)
        settle = slider(500, 2500, 100) { callbacks.settleMs(it.toLong()) }
        automation.addView(settle, LayoutParams(MATCH_PARENT, palette.dp(48)))
        settle.id = View.generateViewId(); settleLabel.labelFor = settle.id
        scanLabel = palette.label("", 13f)
        automation.addView(scanLabel)
        scan = slider(250, 1500, 50) { callbacks.scanMs(it.toLong()) }
        automation.addView(scan, LayoutParams(MATCH_PARENT, palette.dp(48)))
        scan.id = View.generateViewId(); scanLabel.labelFor = scan.id
        automation.addView(palette.label(text(R.string.reading_overlay_poll_detail), 12f), spaced(bottom = 8))
        cooldownLabel = palette.label("", 13f)
        automation.addView(cooldownLabel)
        cooldown = slider(2000, 15000, 500) { callbacks.refreshCooldownMs(it.toLong()) }
        automation.addView(cooldown, LayoutParams(MATCH_PARENT, palette.dp(48)))
        cooldown.id = View.generateViewId(); cooldownLabel.labelFor = cooldown.id
        automation.addView(palette.label(text(R.string.reading_overlay_cooldown_detail), 12f), spaced(bottom = 8))
        automation.addView(sectionLabel(R.string.reading_overlay_sensitivity))
        val sensitivityRow = LinearLayout(context)
        sensitivityButtons = listOf("stable" to R.string.reading_overlay_stable, "balanced" to R.string.reading_overlay_balanced,
            "sensitive" to R.string.reading_overlay_sensitive).mapIndexed { index, (key, label) ->
            key to palette.button(text(label), { callbacks.changeSensitivity(key) }).also { button ->
                sensitivityRow.addView(button, LayoutParams(0, WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = palette.dp(6) })
            }
        }
        automation.addView(sensitivityRow, spaced(bottom = 8))
        automation.addView(palette.label(text(R.string.reading_overlay_sensitivity_detail), 12f), spaced(bottom = 8))
        appearance = disclosure(controlsPage, R.string.reading_overlay_appearance)
        visibilitySwitch = toggle(R.string.reading_overlay_show_translation, R.string.reading_overlay_show_translation_detail, callbacks.visibility, appearance)
        textSizeLabel = palette.label("", 13f)
        appearance.addView(textSizeLabel)
        textSize = slider(14, 34, 1) { callbacks.textSize(it.toFloat()) }
        appearance.addView(textSize, LayoutParams(MATCH_PARENT, palette.dp(48)))
        textSize.id = View.generateViewId(); textSizeLabel.labelFor = textSize.id
        appearance.addView(palette.label(text(R.string.reading_overlay_appearance_detail), 12f), spaced(bottom = 8))
        compactSwitch = toggle(R.string.reading_overlay_compact, R.string.reading_overlay_compact_detail, callbacks.compactHandle, appearance)
        val more = disclosure(controlsPage, R.string.reading_overlay_more)
        more.addView(buttonRow(palette.button(text(R.string.reading_overlay_setup), callbacks.openSetup),
            palette.button(text(R.string.reading_overlay_open_hearth), callbacks.openHearth)), spaced(bottom = 8))
        more.addView(buttonRow(palette.button(text(R.string.reading_overlay_clear), callbacks.clear),
            palette.button(text(R.string.reading_overlay_stop), callbacks.stop).apply { setTextColor(palette.danger) }), spaced(bottom = 8))

        textPage.addView(sectionLabel(R.string.reading_overlay_translation))
        translatedText = textBody()
        textPage.addView(translatedText, spaced(bottom = 8))
        textPage.addView(buttonRow(palette.button(text(R.string.reading_overlay_copy), callbacks.copy),
            palette.button(text(R.string.reading_overlay_speak), { callbacks.speak(false, false) })), spaced(bottom = 10))
        val voices = disclosure(textPage, R.string.reading_overlay_voice_options)
        voices.addView(buttonRow(palette.button(text(R.string.reading_overlay_system_voice), { callbacks.speak(false, true) }),
            palette.button(text(R.string.reading_overlay_stop_voice), callbacks.stopVoice)), spaced(bottom = 8))
        textPage.addView(sectionLabel(R.string.reading_overlay_source))
        originalText = textBody()
        textPage.addView(originalText, spaced(bottom = 8))
        textPage.addView(palette.button(text(R.string.reading_overlay_read_source), { callbacks.speak(true, false) }), spaced(bottom = 12))
        historyButton = palette.button(text(R.string.reading_overlay_history), callbacks.history)
        textPage.addView(historyButton, spaced(bottom = 10))
        textPage.addView(palette.label(text(R.string.reading_overlay_history_detail), 12f), spaced(bottom = 8))
        selectText(false)
        render(state)
    }

    /** Calling render never fires a callback, changes tabs, or resets scroll position. */
    fun render(value: ReadingOverlayUiState) {
        state = value
        rendering = true
        try {
            languageText.text = value.languagePair
            languageText.visibility = if (value.languagePair.isBlank()) GONE else VISIBLE
            primaryButton.text = text(if (value.working) R.string.reading_overlay_reading else R.string.reading_overlay_translate)
            primaryButton.isEnabled = !value.working
            pauseButton.text = text(if (value.paused) R.string.reading_overlay_resume else R.string.reading_overlay_pause)
            pauseButton.isSelected = value.paused
            pauseButton.visibility = if (value.mode == "page") VISIBLE else GONE
            passThrough.isChecked = value.passThrough
            visibilitySwitch.isChecked = value.translationsVisible
            originalSwitch.isChecked = value.showOriginal
            selectedAreaText.text = text(if (value.selectedArea) R.string.reading_overlay_selected_area else R.string.reading_overlay_full_screen)
            styleSelected(pageMode, value.mode == "page")
            styleSelected(manualMode, value.mode == "manual")
            settle.isEnabled = value.mode == "page"
            scan.isEnabled = value.mode == "page"
            cooldown.isEnabled = value.mode == "page"
            sensitivityButtons.forEach { (key, button) -> styleSelected(button, key == value.changeSensitivity); button.isEnabled = value.mode == "page" }
            compactSwitch.isChecked = value.compactHandle
            cooldown.progress = ((value.refreshCooldownMs.coerceIn(2000, 15000) - 2000) / 500).toInt()
            settle.progress = ((value.settleMs.coerceIn(500, 2500) - 500) / 100).toInt()
            scan.progress = ((value.scanMs.coerceIn(250, 1500) - 250) / 50).toInt()
            textSize.progress = (value.textSize.coerceIn(14f, 34f) - 14).roundToInt()
            settleLabel.text = text(R.string.reading_overlay_settle, value.settleMs)
            scanLabel.text = text(R.string.reading_overlay_poll, value.scanMs)
            cooldownLabel.text = text(R.string.reading_overlay_cooldown, value.refreshCooldownMs)
            textSizeLabel.text = text(R.string.reading_overlay_text_size, value.textSize.roundToInt())
            translatedText.textSize = value.textSize
            originalText.textSize = value.textSize
            historyButton.text = text(R.string.reading_overlay_history_count, value.historyCount)
            historyButton.isEnabled = value.historyCount > 0
        } finally { rendering = false }
    }

    fun showReadingText() = selectText(true)
    fun resetToMain() {
        selectText(false)
        expandedSections.forEach { (button, section) -> section.visibility = GONE; updateDisclosure(button, false) }
        scroll.scrollTo(0, 0)
    }

    private fun selectText(value: Boolean) {
                controlsPage.visibility = if (value) GONE else VISIBLE
        textPage.visibility = if (value) VISIBLE else GONE
        styleSelected(controlsTab, !value)
        styleSelected(textTab, value)
        scroll.scrollTo(0, 0)
    }

    private fun textBody() = palette.label("", 20f).apply {
        setTextIsSelectable(true)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setPadding(palette.dp(12), palette.dp(12), palette.dp(12), palette.dp(12))
        background = palette.fill(palette.raised, 14)
        minHeight = palette.dp(64)
    }

    private fun sectionLabel(id: Int) = palette.label(text(id), 14f, strong = true).apply {
        setPadding(0, palette.dp(14), 0, palette.dp(8))
    }

    private fun toggle(title: Int, detail: Int, change: (Boolean) -> Unit, parent: LinearLayout = controlsPage): Switch {
        val group = vertical().apply { setPadding(0, 0, 0, palette.dp(12)) }
        val control = Switch(context).apply {
            text = this@ReadingOverlayControls.text(title)
            textSize = 15f
            setTextColor(palette.ink)
            minHeight = palette.dp(48)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.accent, palette.muted))
            trackTintList = ColorStateList.valueOf(palette.line)
            setOnCheckedChangeListener { _, checked -> if (!rendering) change(checked) }
        }
        group.addView(control, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        group.addView(palette.label(text(detail), 12f))
        parent.addView(group, spaced(bottom = 2))
        return control
    }

    private fun disclosure(parent: LinearLayout, label: Int): LinearLayout {
        val content = vertical().apply { visibility = GONE }
        val button = palette.button(text(label), {}).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        button.setOnClickListener {
            val expanded = content.visibility != VISIBLE
            content.visibility = if (expanded) VISIBLE else GONE
            updateDisclosure(button, expanded)
        }
        updateDisclosure(button, false)
        expandedSections += button to content
        parent.addView(button, spaced(top = 8, bottom = 8))
        parent.addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return content
    }

    private fun updateDisclosure(button: Button, expanded: Boolean) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
            if (expanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float, 0)
        button.compoundDrawablesRelative[2]?.mutate()?.setTint(palette.muted)
        if (Build.VERSION.SDK_INT >= 30) button.stateDescription = text(
            if (expanded) R.string.reading_overlay_expanded else R.string.reading_overlay_collapsed)
    }

    private fun slider(minimum: Int, maximum: Int, step: Int, changed: (Int) -> Unit) = SeekBar(context).apply {
        max = (maximum - minimum) / step
        progressTintList = ColorStateList.valueOf(palette.accent)
        thumbTintList = ColorStateList.valueOf(palette.accent)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !rendering) changed(minimum + progress * step)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun styleSelected(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.background = palette.ripple(if (selected) palette.accentSurface else palette.raised, 12)
        button.setTextColor(if (selected) palette.accent else palette.ink)
    }

    private fun buttonRow(first: Button, second: Button) = LinearLayout(context).apply {
        addView(first, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(second, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = palette.dp(8) })
    }
    private fun spaced(top: Int = 0, bottom: Int = 0) = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = palette.dp(top); bottomMargin = palette.dp(bottom)
    }
    private fun vertical() = LinearLayout(context).apply { orientation = VERTICAL }

    companion object {
        /** Focus only the options window. The compact handle must leave the foreground app in control. */
        fun focusableFlags(base: Int): Int = (base and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
}

private class ReadingOverlayPalette(private val context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val ink = if (dark) Color.rgb(238, 243, 246) else Color.rgb(24, 34, 40)
    val muted = if (dark) Color.rgb(169, 184, 193) else Color.rgb(83, 101, 110)
    val raised = if (dark) Color.rgb(38, 51, 58) else Color.rgb(233, 241, 243)
    val line = if (dark) Color.rgb(83, 108, 116) else Color.rgb(165, 190, 195)
    val accent = if (dark) Color.rgb(98, 226, 207) else Color.rgb(0, 106, 97)
    val accentSurface = if (dark) Color.rgb(31, 73, 70) else Color.rgb(204, 244, 235)
    val danger = if (dark) Color.rgb(255, 172, 165) else Color.rgb(167, 35, 29)
    val paused = if (dark) Color.rgb(255, 206, 126) else Color.rgb(135, 82, 0)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    fun fill(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000), fill(color, radius), null)
    fun surface(radius: Int) = GradientDrawable().apply {
        setColor(if (dark) Color.rgb(19, 31, 37) else Color.rgb(250, 253, 253))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (dark) Color.rgb(59, 77, 84) else Color.rgb(206, 224, 228))
    }
    fun label(value: String, size: Float, strong: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (strong) ink else muted)
        if (strong) setTypeface(typeface, Typeface.BOLD)
    }
    fun button(value: String, action: () -> Unit, primary: Boolean = false) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 14f
        minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
        maxLines = 2; gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = ripple(if (primary) accent else raised, 14)
        setTextColor(if (primary) if (dark) Color.rgb(9, 45, 42) else Color.WHITE else ink)
        setOnClickListener { action() }
    }
}
