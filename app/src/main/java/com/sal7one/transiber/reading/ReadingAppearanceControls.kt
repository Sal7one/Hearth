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
import java.util.Locale

internal fun ReadingOverlayUiState.appearanceSettings() = ReadingOverlaySettings(
    replaceText = replaceText, boxWidthPercent = boxWidthPercent, boxHeightPercent = boxHeightPercent,
    boxBackgroundArgb = boxBackgroundArgb, boxTextArgb = boxTextArgb, boxOpacity = boxOpacity,
).bounded()

/** Shares saved values with setup; render never emits writes or resets the colour disclosure. */
internal class ReadingAppearanceControls(context: Context, private val changed: (ReadingOverlaySettings) -> Unit) : LinearLayout(context) {
    private val palette = ReadingOverlayPalette(context)
    private val text = context.uiText()
    private var state = ReadingOverlaySettings()
    private var rendering = false
    private val replacement: Switch
    private val widthScale: StyleSlider
    private val heightScale: StyleSlider
    private val opacity: StyleSlider
    private val presetButtons: List<Pair<ReadingBoxPreset, Button>>
    private val swatches = mutableListOf<Triple<Boolean, Int, Button>>()
    private val rgb = mutableListOf<Triple<Boolean, Int, StyleSlider>>()

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
        addView(palette.label(text(R.string.reading_style_geometry_help), 12f), spaced())
        colourSwatches(false, R.string.reading_style_background)
        colourSwatches(true, R.string.reading_style_foreground)
        val advanced = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        val advancedButton = palette.button(text(R.string.reading_style_rgb), {})
        advancedButton.setOnClickListener {
            advanced.visibility = if (advanced.visibility == VISIBLE) GONE else VISIBLE
            advancedButton.isSelected = advanced.visibility == VISIBLE
        }
        addView(advancedButton, spaced())
        listOf(false to R.string.reading_style_background, true to R.string.reading_style_foreground).forEach { (foreground, label) ->
            advanced.addView(palette.label(text(label), 14f, strong = true), spaced())
            listOf(16 to R.string.reading_style_red, 8 to R.string.reading_style_green, 0 to R.string.reading_style_blue).forEach { (shift, channel) ->
                val control = styleSlider(channel, 0, 255, 1, advanced) { value -> update {
                    if (foreground) it.copy(boxTextArgb = readingWithRgbChannel(it.boxTextArgb, shift, value))
                    else it.copy(boxBackgroundArgb = readingWithRgbChannel(it.boxBackgroundArgb, shift, value))
                } }
                rgb += Triple(foreground, shift, control)
            }
        }
        addView(advanced, full())
        render(state)
    }

    fun render(settings: ReadingOverlaySettings) {
        state = settings.bounded(); rendering = true
        try {
            replacement.isChecked = state.replaceText
            widthScale.render(state.boxWidthPercent); heightScale.render(state.boxHeightPercent); opacity.render(state.boxOpacity)
            presetButtons.forEach { (preset, button) ->
                button.isSelected = preset.matches(state)
                button.background = palette.ripple(if (button.isSelected) palette.accentSurface else palette.raised, 12)
                button.setTextColor(if (button.isSelected) palette.accent else palette.ink)
            }
            swatches.forEach { (foreground, colour, button) ->
                button.isSelected = colour == if (foreground) state.boxTextArgb else state.boxBackgroundArgb
                button.text = if (button.isSelected) "✓" else ""
            }
            rgb.forEach { (foreground, shift, control) -> control.render(readingRgbChannel(if (foreground) state.boxTextArgb else state.boxBackgroundArgb, shift)) }
        } finally { rendering = false }
    }

    private fun update(change: (ReadingOverlaySettings) -> ReadingOverlaySettings) {
        if (!rendering) {
            val next = change(state).bounded()
            render(next)
            changed(next)
        }
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
