package com.sal7one.transiber.caption

import android.media.AudioManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.voice.MediaVolumeController
import com.sal7one.transiber.voice.MediaVolumeLevel
import com.sal7one.transiber.voice.MediaVolumePort
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Only mounted in the open Audio sheet; no background volume monitor. */
@Composable
internal fun DeviceMediaVolumeUi() {
    val context = LocalContext.current.applicationContext
    val uiText = rememberUiText()
    val controller = remember(context) {
        MediaVolumeController(object : MediaVolumePort {
            private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
            override fun read(): MediaVolumeLevel = MediaVolumeLevel(
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.isVolumeFixed,
            )
            override fun setIndex(index: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        })
    }
    var level by remember { mutableStateOf<MediaVolumeLevel?>(null) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller) {
        while (isActive) {
            try {
                val current = controller.read()
                level = current
                if (!dragging) slider = current.percent.toFloat()
            } catch (e: Exception) { failure = e.message ?: e.toString() }
            delay(750) // Reflect hardware/route changes only while this UI is visible.
        }
    }
    Text(uiText(R.string.ui_device_media_volume, slider.roundToInt()), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = slider.coerceIn(level?.minimumPercent ?: 0f, 100f),
        onValueChange = { dragging = true; slider = it },
        onValueChangeFinished = {
            try {
                val actual = controller.setPercent(slider.roundToInt())
                level = actual; slider = actual.percent.toFloat(); failure = null
            } catch (e: Exception) { failure = e.message ?: e.toString() }
            finally { dragging = false }
        },
        enabled = level?.adjustable == true,
        valueRange = (level?.minimumPercent ?: 0f).coerceAtMost(99f)..100f,
        steps = level?.takeIf { it.adjustable }?.let { (it.maximum - it.minimum - 1).coerceAtLeast(0) } ?: 0,
        modifier = Modifier.fillMaxWidth(),
    )
    if (level?.fixed == true || level?.adjustable == false) {
        Text(uiText(R.string.ui_media_volume_fixed), style = MaterialTheme.typography.bodySmall)
    }
    failure?.let { Text(uiText(R.string.ui_media_volume_error, it), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
    Text(uiText(R.string.ui_device_media_volume_note), style = MaterialTheme.typography.bodySmall)
}
