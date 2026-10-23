package com.sal7one.hearth.desktop.platform

import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCommand
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Registers specific chords only; no keyboard hook, key logging or input-monitoring permission. */
internal class SystemOverlayShortcuts(private val deliver: (OverlayCommand) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val registered = mutableListOf<Pointer>()
    private var handler: Pointer? = null
    private var callback: Carbon.EventHandlerProcPtr? = null // Native callbacks must remain strongly owned.
    private var windowsThread: Thread? = null
    private var windowsThreadId = 0
    @Volatile private var failure: String? = null
    val status: String get() = failure ?: "System shortcuts registered · available outside Hearth"
    private fun emit(id: Int) {
        val command = OverlayCommand.entries.getOrNull(id) ?: return
        if (!closed.get()) EventQueue.invokeLater { if (!closed.get()) deliver(command) }
    }
    fun start(): String {
        try {
            when {
                System.getProperty("os.name").contains("Mac", true) -> startMac()
                System.getProperty("os.name").startsWith("Windows", true) -> startWindows()
                else -> error("System shortcuts are supported on macOS and Windows")
            }
        } catch (error: Throwable) {
            close()
            failure = "System shortcuts unavailable: ${error.message ?: error.javaClass.simpleName}. Focused-app fallback is enabled."
        }
        return status
    }
    private fun startMac() = MacMainQueue.run {
        val api = Carbon.INSTANCE
        val type = Carbon.EventTypeSpec().apply { eventClass = 0x6b657962; eventKind = 5; /* kEventHotKeyPressed, CarbonEvents.h; 6 is key released. */ write() }
        val reference = PointerByReference()
        callback = Carbon.EventHandlerProcPtr { _, event, _ ->
            val id = Carbon.EventHotKeyID()
            val result = api.GetEventParameter(event, 0x2d2d2d2d, 0x686b6964, null, id.size(), null, id)
            if (result != 0) {
                failure = "macOS shortcut event failed: $result. Focused-app fallback is enabled."
                result
            } else {
                id.read()
                if (id.signature == 0x48727468) { emit(id.id); 0 } else -9874 // eventNotHandledErr
            }
        }
        check(api.InstallEventHandler(api.GetEventDispatcherTarget(), callback, 1, arrayOf(type), null, reference) == 0) { "macOS could not install the shortcut handler" }
        handler = reference.value
        for (command in OverlayCommand.entries) {
            val identity = Carbon.EventHotKeyID.ByValue().apply { signature = 0x48727468; id = command.ordinal; write() }
            val hotkey = PointerByReference()
            val result = api.RegisterEventHotKey(command.macCode, Carbon.cmdKey or Carbon.optionKey, identity,
                api.GetEventDispatcherTarget(), 0, hotkey)
            check(result == 0) { "${command.shortcutLabel()} is unavailable (macOS error $result)" }
            registered += hotkey.value
        }
    }
    private fun startWindows() {
        val ready = CountDownLatch(1)
        windowsThread = Thread({
            val api = User32.INSTANCE
            val installed = mutableListOf<Int>()
            try {
                windowsThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                for (command in OverlayCommand.entries) {
                    val id = 0x4800 + command.ordinal
                    check(api.RegisterHotKey(null, id, 0x0001 or 0x0002 or 0x4000, command.key)) {
                        "${command.shortcutLabel(false)} is unavailable (Windows error ${Kernel32.INSTANCE.GetLastError()})"
                    }
                    installed += id
                }
                ready.countDown()
                val message = MSG()
                while (!closed.get()) {
                    val result = api.GetMessage(message, null, 0, 0)
                    check(result != -1) { "Windows shortcut message loop failed" }
                    if (result == 0) break
                    if (message.message == 0x0312) emit(message.wParam.toInt() - 0x4800)
                }
            } catch (error: Throwable) { failure = "System shortcuts unavailable: ${error.message}. Focused-app fallback is enabled." }
            finally { installed.forEach { api.UnregisterHotKey(null, it) }; ready.countDown() }
        }, "Hearth overlay shortcuts").apply { isDaemon = true; start() }
        check(ready.await(5, TimeUnit.SECONDS)) { "Windows shortcut registration timed out" }
        check(failure == null) { failure.orEmpty() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (registered.isNotEmpty() || handler != null) MacMainQueue.run {
            registered.forEach { Carbon.INSTANCE.UnregisterEventHotKey(it) }; registered.clear()
            handler?.let { Carbon.INSTANCE.RemoveEventHandler(it) }; handler = null; callback = null
        }
        if (windowsThreadId != 0) User32.INSTANCE.PostThreadMessage(windowsThreadId, 0x0012, null, null)
        windowsThread?.takeIf { it != Thread.currentThread() }?.join(1500)
    }
}
