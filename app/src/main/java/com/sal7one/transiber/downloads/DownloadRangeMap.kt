package com.sal7one.transiber.downloads

/** A local append-only file may contain authenticated ranges from a larger source object. */
internal object DownloadRangeMap {
    data class Slice(val sourceFirst: Long, val sourceLast: Long, val targetOffset: Long) {
        val bytes get() = Math.addExact(sourceLast - sourceFirst, 1)
    }
    fun remaining(ranges: List<LongRange>, offset: Long, sourceBytes: Long): List<Slice> {
        require(sourceBytes > 0 && offset >= 0)
        var target = 0L
        val slices = ranges.map { range ->
            require(range.first >= 0 && range.last >= range.first && range.last < sourceBytes) { "Invalid pinned source range" }
            val bytes = Math.addExact(range.last - range.first, 1)
            val slice = if (offset < Math.addExact(target, bytes)) {
                val skipped = (offset - target).coerceAtLeast(0)
                Slice(Math.addExact(range.first, skipped), range.last, Math.addExact(target, skipped))
            } else null
            target = Math.addExact(target, bytes)
            slice
        }
        require(offset <= target) { "Saved range payload exceeds the pinned length" }
        return slices.filterNotNull()
    }
}
