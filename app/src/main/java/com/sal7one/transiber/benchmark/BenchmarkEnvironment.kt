package com.sal7one.transiber.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONObject

/** Instantaneous phone/app samples outside inference timing; these are not peak or model RAM. */
internal data class BenchmarkEnvironmentSnapshot(
    val batteryPercent: Int? = null, val batteryCelsius: Double? = null, val thermalStatus: Int? = null,
    val availableSystemMemoryBytes: Long? = null, val systemLowMemory: Boolean? = null,
    val processPssBytes: Long? = null,
) {
    fun json() = JSONObject().put("batteryPercent", batteryPercent ?: JSONObject.NULL)
        .put("batteryCelsius", batteryCelsius ?: JSONObject.NULL).put("thermalStatus", thermalStatus ?: JSONObject.NULL)
        .put("availableSystemMemoryBytes", availableSystemMemoryBytes ?: JSONObject.NULL)
        .put("systemLowMemory", systemLowMemory ?: JSONObject.NULL).put("processPssBytes", processPssBytes ?: JSONObject.NULL)

    companion object {
        fun from(json: JSONObject): BenchmarkEnvironmentSnapshot {
            fun integer(key: String) = if (json.has(key) && !json.isNull(key)) json.getInt(key) else null
            fun long(key: String) = if (json.has(key) && !json.isNull(key)) json.getLong(key) else null
            return BenchmarkEnvironmentSnapshot(integer("batteryPercent"),
                if (json.has("batteryCelsius") && !json.isNull("batteryCelsius")) json.getDouble("batteryCelsius") else null,
                integer("thermalStatus"), long("availableSystemMemoryBytes"),
                if (json.has("systemLowMemory") && !json.isNull("systemLowMemory")) json.getBoolean("systemLowMemory") else null,
                long("processPssBytes"))
        }

        fun capture(context: Context): BenchmarkEnvironmentSnapshot {
            // Unavailable platform telemetry is optional and must never replace an engine result.
            val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (100.0 * level / scale).toInt().takeIf { it in 0..100 } else null
            val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)?.takeIf { it.isFinite() && it in -40.0..100.0 }
            val memory = runCatching {
                context.getSystemService(ActivityManager::class.java)?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }
            }.getOrNull()
            val pss = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024 }.getOrNull()?.takeIf { it > 0 }
            val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull() else null
            return BenchmarkEnvironmentSnapshot(percent, temperature, thermal,
                memory?.availMem?.takeIf { it > 0 }, memory?.lowMemory, pss)
        }
    }
}

internal data class BenchmarkEnvironment(val before: BenchmarkEnvironmentSnapshot, val after: BenchmarkEnvironmentSnapshot) {
    fun json() = JSONObject().put("before", before.json()).put("after", after.json())
    companion object {
        fun from(json: JSONObject) = BenchmarkEnvironment(BenchmarkEnvironmentSnapshot.from(json.getJSONObject("before")),
            BenchmarkEnvironmentSnapshot.from(json.getJSONObject("after")))
    }
}
