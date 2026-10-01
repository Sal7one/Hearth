package com.sal7one.transiber.voice

import kotlin.math.roundToInt

/** Device-wide media volume, not an arbitrary other app's private player gain. */
internal data class MediaVolumeLevel(val index: Int, val minimum: Int, val maximum: Int, val fixed: Boolean) {
    init {
        require(minimum >= 0 && maximum >= minimum && index in minimum..maximum) {
            "Android returned an invalid media volume range: $index ($minimum..$maximum)"
        }
    }
    val adjustable: Boolean get() = !fixed && maximum > minimum
    val percent: Int get() = if (maximum == 0) 0 else (index * 100.0 / maximum).roundToInt()
    val minimumPercent: Float get() = if (maximum == 0) 0f else minimum * 100f / maximum
    fun indexFor(percent: Int): Int = (maximum * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(minimum, maximum)
}

internal interface MediaVolumePort {
    fun read(): MediaVolumeLevel
    fun setIndex(index: Int)
}

/** Reads the current route on every gesture. Never stores/restores global volume. */
internal class MediaVolumeController(private val port: MediaVolumePort) {
    fun read(): MediaVolumeLevel = port.read()
    fun setPercent(percent: Int): MediaVolumeLevel {
        val before = read()
        check(before.adjustable) { "This device has fixed media volume" }
        val target = before.indexFor(percent)
        if (target != before.index) port.setIndex(target)
        return read() // Report the level accepted by Android, not a fictional percentage.
    }
}
