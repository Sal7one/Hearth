package com.sal7one.transiber.benchmark

import android.content.Context

/** Controller boundary: Android IPC owns native execution; the controller owns queue/recovery policy. */
internal interface BenchmarkWorkerSession {
    suspend fun run(request: BenchmarkWorkerProtocol.Request, progress: (String) -> Unit): BenchmarkResult
    fun cancel()
    suspend fun recover(): Boolean

    companion object {
        fun isolated(context: Context): BenchmarkWorkerSession = object : BenchmarkWorkerSession {
            private val worker = BenchmarkIsolatedRunner(context)
            override suspend fun run(request: BenchmarkWorkerProtocol.Request, progress: (String) -> Unit) =
                worker.run(request.candidate, request.clip, request.text, request.referenceText, request.source, request.target,
                    request.inputs, request.measuredPasses, request.attemptTimeoutSeconds, request.jisrDialect, progress)
            override fun cancel() = worker.cancel()
            override suspend fun recover() = worker.recover()
        }
    }
}
