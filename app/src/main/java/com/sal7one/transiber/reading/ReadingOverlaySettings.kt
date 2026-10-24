package com.sal7one.transiber.reading

/** Persisted reading choices. This contract is independent of Activity and capture lifetimes. */
internal data class ReadingOverlaySettings(
    val mode: String = "manual",
    val settleMs: Long = 900,
    val scanMs: Long = 500,
    val refreshCooldownMs: Long = 4_000,
    /** Page-change policy calibrated by ReadingMotion; UI never exposes raw pixel thresholds. */
    val changeSensitivity: String = "balanced",
    val passThrough: Boolean = true,
    val showOriginal: Boolean = false,
    val translationVisible: Boolean = true,
    val textSizeSp: Float = 20f,
    val compactHandle: Boolean = true,
    val motionExclusions: List<ReadingMotionExclusion> = emptyList(),
    /** Replacement is opt-in; regular translation boxes retain their own saved palette. */
    val replaceText: Boolean = false,
    val boxWidthPercent: Int = 100,
    val boxHeightPercent: Int = 100,
    val boxBackgroundArgb: Int = 0xfff8fafc.toInt(),
    val boxTextArgb: Int = 0xff1f2937.toInt(),
    val boxOpacity: Int = 95,
) {
    /** Replacement must erase source ink; regular opacity and pass-through stay saved separately. */
    val opaqueReplacementActive get() = replaceText && translationVisible && !showOriginal

    fun bounded() = copy(
        mode = ReadingTrigger.supportedMode(mode),
        settleMs = settleMs.coerceIn(500, 2_500),
        scanMs = scanMs.coerceIn(250, 1_500),
        refreshCooldownMs = refreshCooldownMs.coerceIn(2_000, 15_000),
        changeSensitivity = changeSensitivity.takeIf { it in setOf("stable", "balanced", "sensitive") } ?: "balanced",
        textSizeSp = textSizeSp.takeIf { it.isFinite() }?.coerceIn(14f, 34f) ?: 20f,
        motionExclusions = ReadingMotionExclusions.bounded(motionExclusions),
        boxWidthPercent = boxWidthPercent.coerceIn(50, 200),
        boxHeightPercent = boxHeightPercent.coerceIn(50, 300),
        boxBackgroundArgb = boxBackgroundArgb or 0xff000000.toInt(),
        boxTextArgb = boxTextArgb or 0xff000000.toInt(),
        boxOpacity = boxOpacity.coerceIn(10, 100),
    )

    companion object {
        /** Existing page/manual choices and display choices survive the redesign. */
        fun from(values: Map<String, *>): ReadingOverlaySettings = ReadingOverlaySettings(
            mode = values["mode"] as? String ?: "manual",
            settleMs = (values["settle"] as? Number)?.toLong() ?: 900,
            scanMs = (values["scan"] as? Number)?.toLong() ?: 500,
            refreshCooldownMs = (values["refresh-cooldown"] as? Number)?.toLong() ?: 4_000,
            changeSensitivity = values["change-sensitivity"] as? String ?: "balanced",
            passThrough = values["pass-through"] as? Boolean ?: true,
            showOriginal = values["show-original"] as? Boolean ?: false,
            translationVisible = values["translation-visible"] as? Boolean ?: true,
            textSizeSp = (values["textSize"] as? Number)?.toFloat() ?: 20f,
            compactHandle = values["compact-handle"] as? Boolean ?: true,
            motionExclusions = ReadingMotionExclusions.decode(values["motion-exclusions"] as? String),
            replaceText = values["replace-text"] as? Boolean ?: false,
            boxWidthPercent = (values["box-width-percent"] as? Number)?.toInt() ?: 100,
            boxHeightPercent = (values["box-height-percent"] as? Number)?.toInt() ?: 100,
            boxBackgroundArgb = (values["box-background-argb"] as? Number)?.toInt() ?: 0xfff8fafc.toInt(),
            boxTextArgb = (values["box-text-argb"] as? Number)?.toInt() ?: 0xff1f2937.toInt(),
            boxOpacity = (values["box-opacity"] as? Number)?.toInt() ?: 95,
        ).bounded()
    }
}
