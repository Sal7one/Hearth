package com.sal7one.transiber.benchmark

import android.content.Context
import com.sal7one.common_jni.CommonJni

/**
 * Native globals belong to a process, so MainActivity's initialization does not
 * carry into the benchmark service. In particular, Vosk needs the application
 * context registered by CommonJni.init() to resolve private model/staging roots.
 * This initializes runtime libraries only; preference migration and model audits
 * remain owned by the main application process.
 */
internal object BenchmarkWorkerInitialization {
    fun initialize(
        context: Context,
        candidate: BenchmarkCandidate,
        commonJni: (Context) -> Unit = CommonJni::init,
        platform: (Context, BenchmarkCandidate) -> Unit = ::initializeBenchmarkPlatform,
    ) {
        val app = context.applicationContext
        commonJni(app)
        platform(app, candidate)
    }
}
