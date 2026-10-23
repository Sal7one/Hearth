package com.sal7one.transiber.benchmark

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Read Android's exit record instead of guessing that every lost Binder was an out-of-memory crash. */
internal object BenchmarkWorkerExitDiagnostics {
    fun describe(context: Context, pid: Int, sinceTimestamp: Long): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, pid, 5)
                .filter { it.pid == pid && it.processName == context.packageName + BenchmarkWorkerProtocol.PROCESS_SUFFIX &&
                    it.timestamp >= sinceTimestamp }
                .maxByOrNull { it.timestamp }
                ?.let { format(it.reason, it.status, it.description) }
        }.getOrNull() // Older/vendor Android versions can omit or delay the public exit record.
    }

    internal fun format(reason: Int, status: Int, description: String?): String {
        val cause = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android stopped the model worker because memory was low"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "The model's native runtime crashed"
            ApplicationExitInfo.REASON_CRASH -> "The model worker crashed"
            ApplicationExitInfo.REASON_ANR -> "Android stopped an unresponsive model worker"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped the model worker for excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Android could not initialize the model worker"
            ApplicationExitInfo.REASON_EXIT_SELF -> "The model worker exited with status $status"
            ApplicationExitInfo.REASON_SIGNALED -> "The model worker received OS signal $status"
            else -> "Android stopped the model worker (exit reason $reason, status $status)"
        }
        val detail = description?.trim()?.takeIf(String::isNotBlank)?.take(512)
        return if (detail == null) cause else "$cause: $detail"
    }
}
