package com.sal7one.transiber.shortcuts

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.i18n.uiText as localizedUiText

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sal7one.transiber.caption.CaptionCaptureService
import com.sal7one.transiber.reading.ReadingOverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

abstract class OverlayTileService : TileService() {
    private val uiText get() = this.localizedUiText()

    internal abstract val shortcut: OverlayShortcut
    private var listening: CoroutineScope? = null
    private val running: StateFlow<Boolean> get() = if (shortcut == OverlayShortcut.CAPTIONS) CaptionCaptureService.running else ReadingOverlayService.running
    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch { running.collect { active ->
                qsTile?.apply {
                    label = uiText(if (shortcut == OverlayShortcut.CAPTIONS) UiR.string.tile_captions else UiR.string.tile_reading)
                    state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    contentDescription = "${uiText(if (shortcut == OverlayShortcut.CAPTIONS) UiR.string.tile_captions else UiR.string.tile_reading)} · ${if (active) uiText(UiR.string.service_tap_to_show_578b6) else uiText(UiR.string.service_tap_to_start_b11de)}"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = if (active) uiText(UiR.string.service_tap_to_show_578b6) else uiText(UiR.string.service_tap_to_start_b11de)
                    updateTile()
                }
            } }
        }
    }
    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { listening?.cancel(); super.onDestroy() }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launch() } else launch()
    }
    private fun launch() {
        val intent = Intent(this, OverlayLaunchActivity::class.java)
            .putExtra(OverlayLaunchActivity.EXTRA, shortcut.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, shortcut.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
class CaptionTileService : OverlayTileService() { override val shortcut = OverlayShortcut.CAPTIONS }
class ReadingTileService : OverlayTileService() { override val shortcut = OverlayShortcut.READING }
