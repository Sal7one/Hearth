package com.sal7one.transiber.ui

/** A settings page is a child of the sheet that opened it, including from simple Home. */
internal class SettingsSheetReturn(
    val origin: AppNavigation,
    val home: Boolean,
    val homeDestination: Int?,
    val destination: Int,
) {
    init {
        require(destination in 0..16)
        require(homeDestination == null || homeDestination in 0..16)
    }

    // Model/download subpages unwind first. A cross-tab root has no path back to
    // the entry page, so Back returns directly to the suspended sheet instead.
    fun reachedFrom(route: AppNavigation): Boolean =
        route.page == destination || !route.containsInCurrentPath(destination)

    private fun save(): List<Int> =
        listOf(destination, if (home) 1 else 0, homeDestination ?: -1) + origin.save()

    companion object {
        private const val MAX_DEPTH = 32

        fun push(history: List<SettingsSheetReturn>, next: SettingsSheetReturn): List<SettingsSheetReturn> =
            (history + next).takeLast(MAX_DEPTH)

        fun saveHistory(history: List<SettingsSheetReturn>): List<Int> =
            listOf(history.size) + history.flatMap { frame -> frame.save().let { listOf(it.size) + it } }

        fun restoreHistory(saved: List<Int>): List<SettingsSheetReturn> {
            val count = saved.firstOrNull() ?: return emptyList()
            if (count !in 0..MAX_DEPTH) return emptyList()
            var offset = 1
            val result = ArrayList<SettingsSheetReturn>(count)
            repeat(count) {
                val size = saved.getOrNull(offset++) ?: return emptyList()
                if (size !in 14..128 || size > saved.size - offset) return emptyList()
                val frame = saved.subList(offset, offset + size)
                offset += size
                if (frame[0] !in 0..16 || frame[1] !in 0..1 || frame[2] !in -1..16) return emptyList()
                val rawRoute = frame.drop(3)
                val origin = AppNavigation.restore(rawRoute)
                if (origin.save() != rawRoute) return emptyList()
                result += SettingsSheetReturn(origin, frame[1] == 1, frame[2].takeIf { it >= 0 }, frame[0])
            }
            return result.takeIf { offset == saved.size } ?: emptyList()
        }
    }
}
