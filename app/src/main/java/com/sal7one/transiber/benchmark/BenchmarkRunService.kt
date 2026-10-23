package com.sal7one.transiber.benchmark

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sal7one.transiber.MainActivity
import com.sal7one.transiber.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample

/** User-started on-device experiments, with visible progress and cancellation outside the screen. */
class BenchmarkRunService : Service() {
    companion object {
        internal const val ACTION_START = "com.sal7one.transiber.benchmark.START"
        internal const val EXTRA_RUN = "benchmark-run-id"
        private const val ACTION_STOP = "com.sal7one.transiber.benchmark.STOP"
        private const val CHANNEL = "hearth-benchmarks"
        private const val NOTIFICATION = 8201
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private var renewing: Job? = null
    private var runId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(EXTRA_RUN)
        if (intent?.action == ACTION_STOP) {
            BenchmarkRunController.stop(id)
            if (!BenchmarkRunController.state.value.running) { releaseSession(); stopSelf(startId) }
            return START_NOT_STICKY
        }
        val current = BenchmarkRunController.state.value
        // Ignore stale commands while a different authorized plan is starting/running.
        // Never let an old notification or redelivered Start tear down the new owner.
        if (current.running && (id != current.runId || intent?.action != ACTION_START)) return START_NOT_STICKY
        // A completed run may still own this service until its sampled observer catches up.
        // Only the exact same live owner counts as a duplicate Start.
        if (current.running && runId == id && runId == current.runId) return START_NOT_STICKY
        releaseSession()
        val notifications = getSystemService(NotificationManager::class.java)
        try {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Model benchmarks", NotificationManager.IMPORTANCE_LOW))
            val initial = notification(BenchmarkRunController.state.value)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, initial)
            if (id == null || !BenchmarkRunController.execute(id)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
            runId = id
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hearth:Benchmark")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
            renewing = scope.launch {
                while (isActive) {
                    delay(30 * 60 * 1000L)
                    val state = BenchmarkRunController.state.value
                    if (state.runId == id && state.running) wakeLock?.acquire(60 * 60 * 1000L)
                }
            }
            observing?.cancel()
            observing = scope.launch {
                BenchmarkRunController.state.sample(750).collect { state ->
                    if (state.runId != id) return@collect
                    try { if (state.running) {
                        // A timeout bounds every acquisition; renewal happens only for active user work.
                        wakeLock?.let { if (!it.isHeld) it.acquire(60 * 60 * 1000L) }
                        notifications.notify(NOTIFICATION, notification(state))
                    } else {
                        val latest = BenchmarkRunController.state.value
                        if (latest.runId == id && !latest.running) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            notifications.notify(NOTIFICATION, notification(state))
                            releaseSession()
                            stopSelf(latestStartId)
                        }
                    } } catch (error: Exception) {
                        BenchmarkRunController.failStart(id, error.message ?: error.toString())
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                    }
                }
            }
        } catch (error: Exception) {
            if (id != null) BenchmarkRunController.failStart(id, error.message ?: error.toString())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(state: BenchmarkRunState): Notification {
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java)
            .putExtra("page", 8).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_captions)
            .setContentTitle(state.title).setContentText(state.error ?: state.status)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state.running).setAutoCancel(!state.running)
            .setSubText("${state.completedRows} results · ${state.failedRows} failures")
        if (state.running) {
            val stop = PendingIntent.getService(this, state.runId.hashCode(), Intent(this, BenchmarkRunService::class.java)
                .setAction(ACTION_STOP).putExtra(EXTRA_RUN, state.runId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Stop comparison", stop).setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun releaseSession() {
        observing?.cancel(); observing = null
        renewing?.cancel(); renewing = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        runId = null
    }

    override fun onDestroy() {
        runId?.let(BenchmarkRunController::stop)
        releaseSession(); scope.cancel()
        super.onDestroy()
    }
}
