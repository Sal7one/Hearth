package com.sal7one.hearth.desktop.service

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.TimeUnit

/**
 * Memory a process tree holds, as the operating system counts it against the computer:
 * committed private bytes on Windows, resident set size elsewhere. Null when it cannot be read;
 * a caller reports "unknown", never a guess.
 */
internal object ProcessMemory {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun treeBytes(root: ProcessHandle): Long? {
        val processes = listOf(root) + root.descendants().toList()
        var total = 0L
        var measured = false
        for (process in processes) {
            if (!process.isAlive) continue
            val bytes = (if (windows) windowsCommit(process.pid()) else residentSet(process.pid())) ?: continue
            total += bytes; measured = true
        }
        return total.takeIf { measured }
    }

    private fun windowsCommit(pid: Long): Long? = runCatching {
        val handle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
        try {
            val counters = MemoryCounters()
            if (ProcessStatus.INSTANCE.K32GetProcessMemoryInfo(handle, counters, counters.size())) counters.PagefileUsage.toLong() else null
        } finally { Kernel32.INSTANCE.CloseHandle(handle) }
    }.getOrNull()

    private fun residentSet(pid: Long): Long? = runCatching {
        val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).redirectErrorStream(true).start()
        val text = process.inputStream.use { it.readNBytes(64).toString(Charsets.US_ASCII).trim() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        text.toLongOrNull()?.times(1024)
    }.getOrNull()
}

/** PROCESS_MEMORY_COUNTERS; PagefileUsage is the commit charge the process holds against the computer. */
@Structure.FieldOrder("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
    "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")
internal class MemoryCounters : Structure() {
    @JvmField var cb: Int = 0
    @JvmField var PageFaultCount: Int = 0
    @JvmField var PeakWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var WorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPeakPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPeakNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var PagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var PeakPagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
}

private interface ProcessStatus : StdCallLibrary {
    fun K32GetProcessMemoryInfo(process: WinNT.HANDLE, counters: MemoryCounters, size: Int): Boolean
    companion object { val INSTANCE: ProcessStatus by lazy { Native.load("kernel32", ProcessStatus::class.java) } }
}
