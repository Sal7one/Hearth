package com.sal7one.transiber.ui.components

import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/** Reuses the same visual picker in setup pages and service-owned Compose overlays. */
@Composable
internal fun HearthColourPicker(
    colour: Int,
    modifier: Modifier = Modifier,
    onPreview: (Int) -> Unit = {},
    onCommit: (Int) -> Unit,
) {
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val palette = MaterialTheme.colorScheme
    val ink = palette.onSurface.toArgb()
    val surface = palette.surface.toArgb()
    val accent = palette.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            HearthColourPickerView(context, colour, onPreview = { preview(it) }, onCommit = { commit(it) }).apply {
                // Caption windows deliberately do not take focus away from the video app.
                // Their visual controls work without a keyboard; setup/editor windows also offer hex.
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = updateHexAvailability()
                    override fun onViewDetachedFromWindow(view: View) = Unit
                })
            }
        },
        update = { picker ->
            picker.setPalette(ink, surface, accent)
            picker.setColour(colour)
            picker.updateHexAvailability()
        },
    )
}

private fun HearthColourPickerView.updateHexAvailability() {
    val flags = (rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
    setHexEnabled(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
}
