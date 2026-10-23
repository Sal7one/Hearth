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
) {
    fun bounded() = copy(
        mode = ReadingTrigger.supportedMode(mode),
        settleMs = settleMs.coerceIn(500, 2_500),
        scanMs = scanMs.coerceIn(250, 1_500),
        refreshCooldownMs = refreshCooldownMs.coerceIn(2_000, 15_000),
        changeSensitivity = changeSensitivity.takeIf { it in setOf("stable", "balanced", "sensitive") } ?: "balanced",
        textSizeSp = textSizeSp.takeIf { it.isFinite() }?.coerceIn(14f, 34f) ?: 20f,
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
        ).bounded()
    }
}
