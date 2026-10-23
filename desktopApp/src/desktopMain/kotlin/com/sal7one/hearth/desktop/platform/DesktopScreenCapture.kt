package com.sal7one.hearth.desktop.platform

import com.sal7one.hearth.desktop.DesktopScreenRegion
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArraySet
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.JOptionPane
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Explicit, user-selected screen pixels. No browser automation, URLs, or application text is inspected. */
internal object DesktopScreenCapture {
    private val excluded=CopyOnWriteArraySet<Window>()
    val available: Boolean get() = !GraphicsEnvironment.isHeadless() && NativeOverlayWindow.supported
    fun register(window: Window) {excluded+=window}
    fun unregister(window: Window) {excluded-=window}

    suspend fun capture(region: DesktopScreenRegion): BufferedImage {
        check(available) {"Screen translation requires a macOS or Windows desktop session"}
        ensureScreenAccess(false)
        val display=GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.firstOrNull {
            it.defaultConfiguration.bounds.contains(region.rectangle())
        } ?: error("The selected screen area is no longer available. Choose the area again after changing displays.")
        val hidden=withContext(Dispatchers.Swing) {
            excluded.filter {it.isVisible}.onEach {it.isVisible=false}
        }
        try {
            // Keep our replacement text out of its own OCR input. Await compositor work
            // after hiding only these owned windows; external application state is untouched.
            if(hidden.isNotEmpty())delay(80)
            return withContext(Dispatchers.IO) {
                val source=Robot(display).createScreenCapture(region.rectangle())
                downsampleScreenImage(source)
            }
        } catch(failure: SecurityException) {
            throw IllegalStateException("Screen capture was denied. On macOS allow Hearth in System Settings → Privacy & Security → Screen & System Audio Recording, then reopen Hearth.",failure)
        } finally {
            withContext(NonCancellable+Dispatchers.Swing) {hidden.filter {it.isDisplayable}.forEach {it.isVisible=true}}
        }
    }

    private fun ensureScreenAccess(request: Boolean) {
        if(!System.getProperty("os.name").contains("Mac",true))return
        val graphics=NativeLibrary.getInstance("CoreGraphics")
        // CoreGraphics declares C bool. Read its low byte rather than treating an
        // ungranted screenshot's black pixels as a legitimate empty page.
        val allowed=graphics.getFunction("CGPreflightScreenCaptureAccess").invokeInt(emptyArray()) and 255 != 0
        val granted=allowed || request && graphics.getFunction("CGRequestScreenCaptureAccess").invokeInt(emptyArray()) and 255 != 0
        check(granted) {"Allow Hearth in System Settings → Privacy & Security → Screen & System Audio Recording, then reopen Hearth to translate screen pixels."}
    }

    /** A screenshot selector on the display under the pointer. Escape always dismisses it. */
    suspend fun selectRegion(): DesktopScreenRegion? {
        check(available) {"Screen translation requires a macOS or Windows desktop session"}
        withContext(Dispatchers.IO) {ensureScreenAccess(true)}
        val display=withContext(Dispatchers.Swing) {
            val environment=GraphicsEnvironment.getLocalGraphicsEnvironment()
            val displays=environment.screenDevices
            val pointer=MouseInfo.getPointerInfo()?.location
            val current=displays.firstOrNull {pointer!=null && it.defaultConfiguration.bounds.contains(pointer)} ?: environment.defaultScreenDevice
            if(displays.size==1)current else {
                val labels=displays.mapIndexed {index,device ->device.defaultConfiguration.bounds.let {"Display ${index+1} · ${it.width} × ${it.height} (${it.x}, ${it.y})"}}
                val chosen=JOptionPane.showInputDialog(null,"Which display contains the page?","Hearth · Select display",JOptionPane.QUESTION_MESSAGE,null,labels.toTypedArray(),labels[displays.indexOf(current)])
                if(chosen==null)null else displays[labels.indexOf(chosen.toString())]
            }
        } ?: return null
        val bounds=display.defaultConfiguration.bounds
        require(bounds.width in 32..8192 && bounds.height in 32..8192 && bounds.width.toLong()*bounds.height<=33_554_432) {"This display exceeds the screen capture bound"}
        val screenshot=withContext(Dispatchers.IO) {Robot(display).createScreenCapture(bounds)}
        return withContext(Dispatchers.Swing) {
            suspendCancellableCoroutine {continuation ->
                val window=JWindow(null as Window?,display.defaultConfiguration)
                var anchor: Point?=null;var end: Point?=null
                fun rectangle():Rectangle? {
                    val a=anchor ?: return null;val b=end ?: return null
                    return Rectangle(minOf(a.x,b.x),minOf(a.y,b.y),kotlin.math.abs(a.x-b.x),kotlin.math.abs(a.y-b.y))
                }
                fun finish(region: DesktopScreenRegion?) {
                    window.dispose();screenshot.flush()
                    if(continuation.isActive)continuation.resume(region)
                }
                val view=object:JComponent() {
                    override fun paintComponent(graphics:Graphics) {
                        val g=graphics.create() as Graphics2D
                        try {
                            g.drawImage(screenshot,0,0,width,height,null)
                            g.color=Color(0,0,0,125);g.fillRect(0,0,width,height)
                            rectangle()?.let {r ->
                                g.drawImage(screenshot,r.x,r.y,r.x+r.width,r.y+r.height,r.x,r.y,r.x+r.width,r.y+r.height,null)
                                g.color=Color(43,218,213);g.stroke=BasicStroke(2f);g.draw(r)
                            }
                            g.color=Color(12,18,26,235);g.fillRoundRect(24,24,440,60,18,18)
                            g.color=Color.WHITE;g.font=Font(Font.SANS_SERIF,Font.BOLD,16)
                            g.drawString("Drag around Japanese text · Escape to cancel",40,60)
                        } finally {g.dispose()}
                    }
                }
                view.cursor=Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                view.addMouseListener(object:MouseAdapter() {
                    override fun mousePressed(event:MouseEvent) {anchor=Point(event.x.coerceIn(0,bounds.width),event.y.coerceIn(0,bounds.height));end=anchor;view.repaint()}
                    override fun mouseReleased(event:MouseEvent) {
                        end=Point(event.x.coerceIn(0,bounds.width),event.y.coerceIn(0,bounds.height))
                        val r=rectangle()
                        if(r!=null && r.width>=32 && r.height>=32) finish(DesktopScreenRegion(bounds.x+r.x,bounds.y+r.y,r.width,r.height))
                        else {anchor=null;end=null;view.repaint()}
                    }
                })
                view.addMouseMotionListener(object:MouseMotionAdapter() {
                    override fun mouseDragged(event:MouseEvent) {
                        end=Point(event.x.coerceIn(0,bounds.width),event.y.coerceIn(0,bounds.height));view.repaint()
                    }
                })
                window.addKeyListener(object:KeyAdapter() {override fun keyPressed(event:KeyEvent) {if(event.keyCode==KeyEvent.VK_ESCAPE)finish(null)}})
                window.isAlwaysOnTop=true;window.contentPane.add(view);window.bounds=bounds
                continuation.invokeOnCancellation {EventQueue.invokeLater {window.dispose();screenshot.flush()}}
                try {window.isVisible=true;window.requestFocus()}
                catch(failure:Exception) {window.dispose();screenshot.flush();if(continuation.isActive)continuation.resumeWithException(failure)}
            }
        }
    }
}

internal fun downsampleScreenImage(source: BufferedImage): BufferedImage {
    val scale=min(1.0,2048.0/maxOf(source.width,source.height))
    if(scale==1.0)return source
    return BufferedImage((source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let {g ->try {g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(source,0,0,width,height,null)}finally{g.dispose();source.flush()}}
    }
}
