package com.sal7one.transiber.reading

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.uiText
import com.sal7one.transiber.ui.components.HearthColourPickerView
import java.util.Locale

internal fun ReadingOverlayUiState.appearanceSettings() = ReadingOverlaySettings(
    showOriginal = showOriginal, translationVisible = translationsVisible, passThrough = passThrough,
    replaceText = replaceText, boxWidthPercent = boxWidthPercent, boxHeightPercent = boxHeightPercent,
    boxBackgroundArgb = boxBackgroundArgb, boxTextArgb = boxTextArgb, boxOpacity = boxOpacity,
).bounded()

/** Shares saved values with setup; render never emits writes or resets the open picker. */
internal class ReadingAppearanceControls(context: Context, private val changed: (ReadingOverlaySettings) -> Unit) : LinearLayout(context) {
    private val palette = ReadingOverlayPalette(context)
    private val text = context.uiText()
    private var state = ReadingOverlaySettings()
    private var rendering = false
    private val replacement: Switch
    private val widthScale: StyleSlider
    private val heightScale: StyleSlider
    private val opacity: StyleSlider
    private val opaqueHelp: TextView
    private val presetButtons: List<Pair<ReadingBoxPreset, Button>>
    private val swatches = mutableListOf<Triple<Boolean, Int, Button>>()
    private val colourPickers = mutableListOf<Pair<Boolean, HearthColourPickerView>>()
    private val colourButtons = mutableListOf<Pair<Boolean, Button>>()
    private var activeColour: Boolean? = null

    init {
        orientation = VERTICAL
        replacement = Switch(context).apply {
            text = this@ReadingAppearanceControls.text(R.string.reading_style_replace)
            setTextColor(palette.ink); textSize = 15f; minHeight = palette.dp(48)
            setOnCheckedChangeListener { _, checked -> update { it.copy(replaceText = checked) } }
        }
        addView(replacement, full())
        addView(palette.label(text(R.string.reading_style_replace_help), 12f), spaced())
        addView(palette.label(text(R.string.reading_style_saved_palette), 12f), spaced())
        val presetRows = listOf(
            listOf(ReadingBoxPreset.LIGHT to R.string.reading_style_light, ReadingBoxPreset.DARK to R.string.reading_style_dark),
            listOf(ReadingBoxPreset.WARM to R.string.reading_style_warm, ReadingBoxPreset.HIGH_CONTRAST to R.string.reading_style_contrast),
        )
        presetButtons = presetRows.flatMap { entries ->
            val row = LinearLayout(context)
            val buttons = entries.mapIndexed { index, (preset, label) ->
                val button = palette.button(text(label), { update { preset.applyTo(it) } })
                row.addView(button, LayoutParams(0, WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = palette.dp(8) })
                preset to button
            }
            addView(row, spaced()); buttons
        }
        widthScale = styleSlider(R.string.reading_style_width, 50, 200, 5) { value -> update { it.copy(boxWidthPercent = value) } }
        heightScale = styleSlider(R.string.reading_style_height, 50, 300, 5) { value -> update { it.copy(boxHeightPercent = value) } }
        opacity = styleSlider(R.string.reading_style_opacity, 10, 100, 5) { value -> update { it.copy(boxOpacity = value) } }
        opaqueHelp = palette.label(text(R.string.reading_style_opaque_help), 12f)
        addView(opaqueHelp, spaced())
        addView(palette.label(text(R.string.reading_style_geometry_help), 12f), spaced())
        colourControls(false, R.string.reading_style_background)
        colourControls(true, R.string.reading_style_foreground)
        render(state)
    }

    fun render(settings: ReadingOverlaySettings) {
        state = settings.bounded(); rendering = true
        try {
            replacement.isChecked = state.replaceText
            widthScale.render(state.boxWidthPercent); heightScale.render(state.boxHeightPercent)
            opacity.render(if (state.replaceText) 100 else state.boxOpacity)
            opacity.slider.isEnabled = !state.replaceText
            opaqueHelp.visibility = if (state.replaceText) VISIBLE else GONE
            presetButtons.forEach { (preset, button) ->
                button.isSelected = preset.matches(state)
                button.background = palette.ripple(if (button.isSelected) palette.accentSurface else palette.raised, 12)
                button.setTextColor(if (button.isSelected) palette.accent else palette.ink)
            }
            swatches.forEach { (foreground, colour, button) ->
                button.isSelected = colour == if (foreground) state.boxTextArgb else state.boxBackgroundArgb
                button.text = if (button.isSelected) "✓" else ""
            }
            colourButtons.forEach { (foreground, button) ->
                val label = text(if (foreground) R.string.reading_style_foreground else R.string.reading_style_background)
                val colour = if (foreground) state.boxTextArgb else state.boxBackgroundArgb
                button.text = text(R.string.reading_style_choose_colour, label, String.format(Locale.ROOT, "#%06X", colour and 0xffffff))
                button.isSelected = activeColour == foreground
            }
            colourPickers.forEach { (foreground, picker) ->
                picker.setColour(if (foreground) state.boxTextArgb else state.boxBackgroundArgb)
                picker.visibility = if (activeColour == foreground) VISIBLE else GONE
            }
        } finally { rendering = false }
    }

    private fun update(change: (ReadingOverlaySettings) -> ReadingOverlaySettings) {
        if (!rendering) {
            val next = change(state).bounded()
            render(next)
            changed(next)
        }
    }

    private fun colourControls(foreground: Boolean, label: Int) {
        colourSwatches(foreground, label)
        val button = palette.button("", {
            activeColour = if (activeColour == foreground) null else foreground
            render(state)
        }).apply {
            tag = if (foreground) "reading-text-colour-picker" else "reading-background-colour-picker"
        }
        colourButtons += foreground to button
        addView(button, spaced())
        val picker = HearthColourPickerView(context,
            if (foreground) state.boxTextArgb else state.boxBackgroundArgb,
            onCommit = { colour -> update {
                if (foreground) it.copy(boxTextArgb = colour) else it.copy(boxBackgroundArgb = colour)
            } },
        ).apply {
            visibility = GONE
            setPalette(palette.ink, palette.raised, palette.accent)
        }
        colourPickers += foreground to picker
        addView(picker, spaced())
    }

    private fun colourSwatches(foreground: Boolean, label: Int) {
        addView(palette.label(text(label), 14f, strong = true), spaced())
        readingBoxColourChoices.chunked(3).forEach { colours ->
            val row = LinearLayout(context)
            colours.forEachIndexed { index, colour ->
                val button = palette.button("", { update { if (foreground) it.copy(boxTextArgb = colour) else it.copy(boxBackgroundArgb = colour) } }).apply {
                    contentDescription = this@ReadingAppearanceControls.text(R.string.reading_style_colour_choice,
                        this@ReadingAppearanceControls.text(label), String.format(Locale.ROOT, "#%06X", colour and 0xffffff))
                    background = palette.ripple(colour, 12)
                    setTextColor(if (Color.luminance(colour) > .5) Color.BLACK else Color.WHITE)
                }
                row.addView(button, LayoutParams(0, palette.dp(48), 1f).apply { if (index > 0) marginStart = palette.dp(8) })
                swatches += Triple(foreground, colour, button)
            }
            addView(row, spaced())
        }
    }

    private fun styleSlider(label: Int, minimum: Int, maximum: Int, step: Int,
        parent: LinearLayout = this, change: (Int) -> Unit): StyleSlider {
        val title = palette.label("", 13f)
        parent.addView(title, full())
        val slider = SeekBar(context).apply {
            id = View.generateViewId(); contentDescription = text(label)
            max = (maximum - minimum) / step
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !rendering) change(minimum + progress * step)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        title.labelFor = slider.id
        parent.addView(slider, LayoutParams(MATCH_PARENT, palette.dp(48)))
        return StyleSlider(title, slider, label, minimum, step)
    }

    private inner class StyleSlider(val title: TextView, val slider: SeekBar, val label: Int, val minimum: Int, val step: Int) {
        fun render(value: Int) { title.text = text(R.string.reading_style_value, text(label), value); slider.progress = (value - minimum) / step }
    }
    private fun full() = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    private fun spaced() = full().apply { bottomMargin = palette.dp(10) }
}
