package com.sal7one.transiber.reading

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional event/key observer. Never reads nodes, clicks, or injects reader gestures. */
class ReadingAccessibilityService : AccessibilityService() {
    private var ownsVolume=false
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val service=ReadingOverlayService.active ?: return
        if(event==null)return
        if(event.packageName?.toString()==packageName) {
            if(event.className?.toString() in setOf("com.sal7one.transiber.MainActivity","com.sal7one.transiber.reading.ReadingStartActivity"))service.readerChanged(packageName)
            return
        }
        if(event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED)
            service.scroll(event.scrollDeltaY.takeIf {it!=0} ?: event.scrollDeltaX, event.packageName?.toString().orEmpty())
        else if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            service.readerChanged(event.packageName?.toString().orEmpty())
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if(event.keyCode!=KeyEvent.KEYCODE_VOLUME_UP)return false
        if(event.action==KeyEvent.ACTION_UP && ownsVolume) { ownsVolume=false;return true }
        val service=ReadingOverlayService.active ?: return false
        if(!service.volumeShortcut || service.paused)return false
        if(event.action==KeyEvent.ACTION_DOWN) {
            if(event.repeatCount==0) {ownsVolume=true;service.requestCapture(false)}
            return ownsVolume
        }
        return false
    }
    override fun onInterrupt() { ownsVolume=false }
    override fun onDestroy() { ownsVolume=false;super.onDestroy() }
}
