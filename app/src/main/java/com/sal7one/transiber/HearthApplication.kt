package com.sal7one.transiber

import android.app.Application
import com.sal7one.transiber.preferences.StartupModelAudit

/** All entry points share asynchronous preference migration and model availability checks. */
class HearthApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupModelAudit.start(this)
    }
}
