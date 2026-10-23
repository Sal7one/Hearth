package com.sal7one.transiber

import android.app.Application
import com.sal7one.transiber.preferences.StartupModelAudit

/** All entry points share asynchronous preference migration and model availability checks. */
class HearthApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A benchmark worker owns only a disposable native session. Starting migration,
        // DataStore or download/model audits here would race the main application's stores.
        if (com.sal7one.transiber.benchmark.BenchmarkWorkerService.isWorkerProcess(packageName)) return
        StartupModelAudit.start(this)
    }
}
