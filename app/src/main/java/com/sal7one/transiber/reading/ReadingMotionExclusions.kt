package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val MAX_MOTION_EXCLUSIONS = 4

/** Normalized capture coordinates, scoped to the exact capture geometry the user selected. */
internal data class ReadingMotionExclusion(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val screenWidth: Int, val screenHeight: Int,
) {
    fun valid(): Boolean = screenWidth in 8..16_384 && screenHeight in 8..16_384 &&
        listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right > left && bottom > top &&
        (right * screenWidth).roundToInt() - (left * screenWidth).roundToInt() >= 8 &&
        (bottom * screenHeight).roundToInt() - (top * screenHeight).roundToInt() >= 8

    /** A changed capture size must not move a saved exclusion silently. */
    fun cropFor(width: Int, height: Int): PixelCrop? {
        if (!valid() || width != screenWidth || height != screenHeight) return null
        return PixelCrop((left * width).roundToInt(), (top * height).roundToInt(),
            (right * width).roundToInt(), (bottom * height).roundToInt())
    }

    companion object {
        fun fromCrop(crop: PixelCrop, screenWidth: Int, screenHeight: Int): ReadingMotionExclusion? {
            if (screenWidth !in 8..16_384 || screenHeight !in 8..16_384 || crop.left < 0 || crop.top < 0 ||
                crop.right > screenWidth || crop.bottom > screenHeight || crop.width < 8 || crop.height < 8) return null
            return ReadingMotionExclusion(crop.left.toFloat() / screenWidth, crop.top.toFloat() / screenHeight,
                crop.right.toFloat() / screenWidth, crop.bottom.toFloat() / screenHeight, screenWidth, screenHeight).takeIf { it.valid() }
        }
    }
}

internal object ReadingMotionExclusions {
    private const val MAX_SAVED_CHARACTERS = 4096
    fun bounded(values: List<ReadingMotionExclusion>): List<ReadingMotionExclusion> =
        values.filter { it.valid() }.distinct().take(MAX_MOTION_EXCLUSIONS)

    fun encode(values: List<ReadingMotionExclusion>): String {
        val areas = JSONArray()
        for (area in bounded(values)) areas.put(JSONObject().put("left", area.left).put("top", area.top)
            .put("right", area.right).put("bottom", area.bottom).put("width", area.screenWidth).put("height", area.screenHeight))
        return JSONObject().put("version", 1).put("areas", areas).toString()
    }

    fun decode(raw: String?): List<ReadingMotionExclusion> {
        if (raw.isNullOrBlank() || raw.length > MAX_SAVED_CHARACTERS) return emptyList()
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version") != 1) return emptyList()
            val areas = root.optJSONArray("areas") ?: return emptyList()
            val decoded = mutableListOf<ReadingMotionExclusion>()
            for (index in 0 until areas.length()) {
                val area = areas.optJSONObject(index) ?: continue
                val value = ReadingMotionExclusion(area.optDouble("left", Double.NaN).toFloat(), area.optDouble("top", Double.NaN).toFloat(),
                    area.optDouble("right", Double.NaN).toFloat(), area.optDouble("bottom", Double.NaN).toFloat(), area.optInt("width"), area.optInt("height"))
                if (value.valid() && value !in decoded) decoded += value
                if (decoded.size == MAX_MOTION_EXCLUSIONS) break
            }
            decoded
        } catch (_: Exception) { emptyList() }
    }
}
