package com.sal7one.transiber.benchmark

import android.content.Context
import com.google.mlkit.common.MlKit
import com.sal7one.transiber.translation.TranslationOptions

/** The SDK's manifest initializer belongs to the main process, not the disposable worker. */
internal fun initializeBenchmarkPlatform(context: Context, candidate: BenchmarkCandidate) {
    if (candidate.id == TranslationOptions.ML_KIT) MlKit.initialize(context.applicationContext)
}
