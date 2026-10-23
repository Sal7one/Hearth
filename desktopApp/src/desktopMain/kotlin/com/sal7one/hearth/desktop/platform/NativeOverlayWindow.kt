package com.sal7one.hearth.desktop.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import java.util.concurrent.atomic.AtomicReference

internal object MacMainQueue {
    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val send by lazy { objc.getFunction("objc_msgSend") }
    private val selectors = java.util.concurrent.ConcurrentHashMap<String, Pointer>()
    fun selector(name: String): Pointer = selectors.getOrPut(name) { objc.getFunction("sel_registerName").invokePointer(arrayOf(name)) }
    fun cls(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    fun pointer(target: Pointer?, name: String, vararg arguments: Any?): Pointer? = send.invokePointer(arrayOf(target, selector(name), *arguments))
    fun number(target: Pointer?, name: String): Long = send.invokeLong(arrayOf(target, selector(name)))
    fun decimal(target: Pointer?, name: String): Double = send.invokeDouble(arrayOf(target, selector(name)))
    fun set(target: Pointer, name: String, value: Any) { send.invokeVoid(arrayOf(target, selector(name), value)) }
    interface Dispatch : Library { fun dispatch_sync_f(queue: Pointer, context: Pointer?, callback: Work) }
    interface Work : Callback { fun invoke(context: Pointer?) }
    private val dispatch by lazy { Native.load("System", Dispatch::class.java) }
    private val queue by lazy { NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }
    fun <T> run(block: () -> T): T {
        check(!java.awt.EventQueue.isDispatchThread()) {
            "AppKit bridge must run off AWT's event thread to avoid accessibility deadlocks"
        }
        if (number(cls("NSThread"), "isMainThread") != 0L) return block()
        val result = AtomicReference<Result<T>>()
        val callback = object : Work { override fun invoke(context: Pointer?) { result.set(runCatching(block)) } }
        try { dispatch.dispatch_sync_f(queue, null, callback) }
        finally { java.lang.ref.Reference.reachabilityFence(callback) }
        return result.get().getOrThrow()
    }
}

/** Native operations affect only Hearth's own caption window; no external app is inspected. */
internal object NativeOverlayWindow {
    val supported: Boolean get() = System.getProperty("os.name").let { it.contains("Mac", true) || it.startsWith("Windows", true) }
    /** Handle captured from ComposeWindow.windowHandle on AWT, never a title-based lookup. */
    fun apply(handle: Long, clickThrough: Boolean) {
        check(handle != 0L) { "The caption window is not ready" }
        when {
            System.getProperty("os.name").contains("Mac", true) -> MacMainQueue.run {
                // The window may have closed while this call was queued. Resolve the exact
                // live handle on AppKit before dereferencing it; duplicate titles are valid.
                val windows = MacMainQueue.pointer(MacMainQueue.pointer(MacMainQueue.cls("NSApplication"), "sharedApplication"), "windows")
                val target = (0 until MacMainQueue.number(windows, "count").coerceIn(0, 500)).firstNotNullOfOrNull { index ->
                    MacMainQueue.pointer(windows, "objectAtIndex:", index)?.takeIf { Pointer.nativeValue(it) == handle }
                } ?: error("The caption window has closed")
                // Compatible utility/full-screen flags; avoid contradictory primary/auxiliary bits.
                MacMainQueue.set(target, "setCollectionBehavior:", 1L or 8L or 64L or 256L or 4096L or (1L shl 18))
                MacMainQueue.set(target, "setLevel:", 3L)
                MacMainQueue.set(target, "setHidesOnDeactivate:", 0.toByte())
                MacMainQueue.set(target, "setIgnoresMouseEvents:", if (clickThrough) 1.toByte() else 0.toByte())
                // Apply transparency AFTER native presentation/focus changes. AWT/AppKit
                // can rebuild backing state when those attributes or the renderer change.
                val clear = requireNotNull(MacMainQueue.pointer(MacMainQueue.cls("NSColor"), "clearColor"))
                MacMainQueue.set(target, "setOpaque:", 0.toByte())
                MacMainQueue.set(target, "setBackgroundColor:", clear)
                MacMainQueue.set(target, "setHasShadow:", 0.toByte())
                MacMainQueue.pointer(target, "contentView")?.let { MacMainQueue.set(it, "setNeedsDisplay:", 1.toByte()) }
                check(MacMainQueue.number(target, "isOpaque") == 0L &&
                    MacMainQueue.decimal(MacMainQueue.pointer(target, "backgroundColor"), "alphaComponent") == 0.0) {
                    "macOS did not apply caption window transparency"
                }
                check((MacMainQueue.number(target, "ignoresMouseEvents") != 0L) == clickThrough) { "macOS did not apply click-through" }
            }
            System.getProperty("os.name").startsWith("Windows", true) -> {
                val api = User32.INSTANCE
                val hwnd = HWND(Pointer(handle))
                check(api.IsWindow(hwnd)) { "The caption window has closed" }
                val old = api.GetWindowLong(hwnd, -20)
                val transparent = 0x20; val noActivate = 0x08000000
                val next = if (clickThrough) old or transparent or noActivate or 0x80000 else old and transparent.inv() and noActivate.inv()
                Native.setLastError(0)
                val previous = api.SetWindowLong(hwnd, -20, next)
                check(previous != 0 || Native.getLastError() == 0) { "Windows click-through failed: ${Native.getLastError()}" }
                check((api.GetWindowLong(hwnd, -20) and transparent != 0) == clickThrough) { "Windows did not apply click-through" }
            }
            else -> check(!clickThrough) { "Native click-through is available on macOS and Windows" }
        }
    }
}
