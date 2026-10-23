package com.sal7one.transiber.benchmark

import android.content.Context
import com.sal7one.transiber.translation.TranslationOptions

/** FOSS must never initialize or delegate work to an unavailable network-capable SDK. */
internal fun initializeBenchmarkPlatform(@Suppress("UNUSED_PARAMETER") context: Context, candidate: BenchmarkCandidate) {
    check(candidate.id != TranslationOptions.ML_KIT) { "ML Kit is unavailable in the offline app" }
}
