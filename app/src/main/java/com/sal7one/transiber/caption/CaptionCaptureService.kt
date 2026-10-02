package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.i18n.uiText as localizedUiText

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Owns one consent, recorder, caption engine and overlay. Commands run on Main. */
class CaptionCaptureService : Service() {
    private val uiText get() = this.localizedUiText()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: CaptionEngineController
    private lateinit var overlay: CaptionOverlayController
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: AudioRecord? = null
    private var reader: Job? = null
    private var startup: Job? = null
    private var runtimeLease: com.sal7one.transiber.runtime.LocalWorkGate.Lease? = null
    @Volatile private var capturing = false
    private var active = false
        set(value) { field = value; runningState.value = value }
    private var source = CaptionSource.PLAYBACK_CAPTURE
    private var overlayVisible = true
    private var failure: String? = null

    override fun onCreate() {
        super.onCreate()
        engine = CaptionEngineController(this)
        val history = com.sal7one.transiber.caption.history.CaptionHistoryRepository.get(this)
        val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine.historyRecorder = com.sal7one.transiber.caption.history.CaptionHistoryRecorder(historyScope,
            history::record, { message -> history.reportSavingError(message); engine.reportHistoryError(message) },
            com.sal7one.transiber.caption.history.CaptionHistoryCapture(deletions = { history.deletionVersion }))
        engine.historyRecorder!!.job.invokeOnCompletion { historyScope.cancel() }
        overlay = CaptionOverlayController(this, engine)
        scope.launch { com.sal7one.transiber.i18n.AppLocale.revision.collect { if (active) updateNotification() } }
        scope.launch { com.sal7one.transiber.shortcuts.OverlaySetupVisibility.active.collect { overlay.suppressForSetup(it) } }
        scope.launch { overlay.config.collect { if (active) updateNotification() } }
        scope.launch {
            engine.state.collect { state ->
                if (active && state.status == CaptionEngineController.Status.ERROR && failure == null) {
                    fail(state.error ?: "Caption engine failed")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null

    /** Remote/media-row controls: play-pause mirrors the notification action, stop kills captions and TTS. */
    private fun syncMediaSession(paused: Boolean) {
        // A caption overlay on a phone must not take media-key ownership from
        // the video/music app underneath it. TV remote controls opt into it.
        if (!TvControls.isTvDevice(this)) return
        val session = mediaSession ?: android.support.v4.media.session.MediaSessionCompat(this, "hearth-captions").also { created ->
            created.setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay() { onStartCommand(Intent().setAction(ACTION_RESUME), 0, 0) }
                override fun onPause() { onStartCommand(Intent().setAction(ACTION_PAUSE_ONLY), 0, 0) }
                override fun onStop() { onStartCommand(Intent().setAction(ACTION_STOP), 0, 0) }
            })
            mediaSession = created
            created.isActive = true
        }
        session.setPlaybackState(android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY
                or android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE
                or android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE
                or android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
            .setState(if (paused) android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED else android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,
                android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
            .build())
    }

    private fun releaseMediaSession() {
        mediaSession?.run { isActive = false; release() }
        mediaSession = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCaptioning()
            ACTION_CENTER -> if (active) {
                overlayVisible = true
                overlay.centerOverlay()
                updateNotification()
            } else stopSelf(startId)
            ACTION_PAUSE, ACTION_RESUME, ACTION_PAUSE_ONLY -> if (active && failure == null) {
                overlay.updateConfig { it.copy(paused = TvControls.pausedForAction(requireNotNull(intent.action), it.paused)) }
            } else if (!active) stopSelf(startId)
            ACTION_SILENCE -> if (active) {
                engine.silenceSpeaker()
                overlay.updateConfig { it.copy(speakCaptions = false) }
            } else stopSelf(startId)
            ACTION_SETTINGS -> if (active) {
                overlayVisible = true
                overlay.centerOverlay(showSettings = true)
                updateNotification()
            } else stopSelf(startId)
            ACTION_VISIBILITY -> if (active) {
                overlayVisible = !overlayVisible
                if (overlayVisible) overlay.centerOverlay() else overlay.toggleVisibility()
                updateNotification()
            } else stopSelf(startId)
            ACTION_START -> {
                if (active) { overlay.centerOverlay(); return START_NOT_STICKY }
                try { runtimeLease = com.sal7one.transiber.runtime.LocalWorkGate.acquire("Captions") }
                catch (e: Exception) {
                    android.widget.Toast.makeText(this, e.message ?: e.toString(), android.widget.Toast.LENGTH_LONG).show()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                source = intent.getSerializableExtra(EXTRA_SOURCE) as? CaptionSource ?: CaptionSource.PLAYBACK_CAPTURE
                active = true
                if (!startForegroundCapture()) return START_NOT_STICKY
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJECTION_RESULT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_RESULT)
                startup = scope.launch {
                    try {
                        check(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            "Audio capture: RECORD_AUDIO permission is missing"
                        }
                        val config = CaptionConfigStore.config(this@CaptionCaptureService).first().copy(source = source, paused = false)
                        overlay.show(source)
                        val record = when (source) {
                                CaptionSource.MIC -> buildMicRecord()
                                CaptionSource.PLAYBACK_CAPTURE -> buildPlaybackRecord(requireNotNull(data) {
                                    "Playback capture: media projection consent is missing"
                                })
                        }
                        if (!active) { record.release(); return@launch }
                        recorder = record
                        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                        // The audio loop may run while the model connects; the
                        // controller admits audio only once its engine is ready.
                        startReading(record)
                        engine.start(config) { if (active) updateNotification() }

                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail("Capture: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            else -> if (!active) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCapture(): Boolean = try {
        val type = if (source == CaptionSource.MIC) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), type)
        else startForeground(NOTIFICATION_ID, notification())
        syncMediaSession(paused = false)
        true
    } catch (e: Exception) {
        failure = "Foreground service: ${e.message ?: e.javaClass.simpleName}"
        engine.reportError(failure!!)
        android.widget.Toast.makeText(this, failure, android.widget.Toast.LENGTH_LONG).show()
        active = false
        stopSelf()
        false
    }

    private fun bufferBytes(): Int {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioRecord.getMinBufferSize returned $min" }
        return maxOf(min * 2, READ_SAMPLES * 4)
    }

    private fun buildMicRecord() = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes())

    private suspend fun buildPlaybackRecord(data: Intent): AudioRecord {
        check(Build.VERSION.SDK_INT >= 29) { "Device audio capture requires Android 10 or newer" }
        // Projection ownership is serialized on Main, including revocation callbacks.
        val token = withContext(Dispatchers.Main.immediate) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            requireNotNull(manager.getMediaProjection(Activity.RESULT_OK, data)) { "MediaProjectionManager returned no projection" }.also { token ->
                projection = token
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (projection === token && active) fail("Playback capture: MediaProjection stopped by the system or user")
                    }
                }
                projectionCallback = callback
                token.registerCallback(callback, Handler(Looper.getMainLooper()))
            }
        }
        val playback = AudioPlaybackCaptureConfiguration.Builder(token)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        return AudioRecord.Builder().setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setAudioPlaybackCaptureConfig(playback).setBufferSizeInBytes(bufferBytes()).build()
    }

    private fun startReading(record: AudioRecord) {
        capturing = true
        reader = scope.launch(Dispatchers.IO) {
            val pcm = ShortArray(READ_SAMPLES)
            var silentSamples = 0
            try {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not enter RECORDSTATE_RECORDING" }
                while (capturing && isActive) {
                    val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (!capturing) break
                    check(count >= 0) { "AudioRecord.read returned $count" }
                    if (count == 0) { delay(10); continue }
                    val silent = (0 until count).all { pcm[it] == 0.toShort() }
                    silentSamples = if (silent) (silentSamples + count).coerceAtMost(SAMPLE_RATE * 3) else 0
                    engine.markCaptureSilent(silentSamples >= SAMPLE_RATE * 2)
                    // Silence is part of the audio timeline. It endpoints VAD and
                    // lets the translation server finish phrases without reconnecting.
                    engine.pushAudio(pcm, count)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.Main) { if (capturing) fail("Capture: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    private fun releaseCapture() {
        capturing = false
        val record = recorder
        recorder = null
        runCatching { record?.stop() }
        if (reader == null) record?.release() // otherwise the reader owns release
        val token = projection
        projection = null
        projectionCallback?.let { callback -> runCatching { token?.unregisterCallback(callback) } }
        projectionCallback = null
        runCatching { token?.stop() }
    }

    private fun fail(message: String) {
        if (!active) return
        failure = message
        releaseCapture()
        engine.pauseProcessing()
        engine.reportError(message)
        overlayVisible = true
        overlay.centerOverlay()
        updateNotification()
    }

    private fun stopCaptioning() {
        active = false
        releaseMediaSession()
        startup?.cancel()
        releaseCapture()
        overlay.hide()
        engine.pauseProcessing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CaptionConfigStore.onOrientationChanged(newConfig.orientation)
        overlay.refreshBounds()
        if (active) updateNotification()
    }

    override fun onDestroy() {
        active = false
        releaseMediaSession()
        releaseCapture()
        overlay.destroy()
        engine.shutdown()
        engine.historyRecorder?.close() // Drain accepted text writes on its IO scope, outside service cancellation.
        val lease = runtimeLease.also { runtimeLease = null }
        CoroutineScope(Dispatchers.IO).launch { try { engine.awaitReleased() } finally { lease?.close() } }
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        syncMediaSession(paused = engine.currentConfig.paused)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, uiText(UiR.string.service_live_captions_83fd1), NotificationManager.IMPORTANCE_LOW))
        val paused = engine.currentConfig.paused
        val tapThrough = overlay.config.value.tapThrough
        fun command(id: Int, action: String) = PendingIntent.getService(this, id,
            Intent(this, CaptionCaptureService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (failure != null) uiText(UiR.string.service_live_captions_error_9f465) else if (paused) uiText(UiR.string.service_live_captions_paused_b59ce) else uiText(UiR.string.service_live_captions_83fd1))
            .setContentText(failure ?: if (tapThrough) uiText(UiR.string.service_tap_through_is_on_tap_the_lock_handle_to_restore_c_2e5ea) else if (paused) uiText(UiR.string.service_audio_is_not_sent_to_the_speech_engine_859aa) else "${uiText.label(source)} · ${uiText.label(engine.currentConfig.mode)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(failure ?: uiText(UiR.string.service_1_s_tap_this_notification_to_recover_the_bubble_an_16fe0, uiText.label(source))))
            .setContentIntent(command(2, ACTION_CENTER)).setOngoing(true).setOnlyAlertOnce(true)
            .apply { mediaSession?.sessionToken?.let { token -> setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(token)) } }
            .apply { if (failure == null) addAction(0, if (paused) uiText(UiR.string.service_resume_b3bd0) else uiText(UiR.string.service_pause_78196), command(1, ACTION_PAUSE)) }
            .apply { if (failure == null) addAction(0, uiText(UiR.string.service_controls_f3d21), command(6, ACTION_SETTINGS)) }
            .addAction(0, uiText(UiR.string.service_stop_9e253), command(3, ACTION_STOP))
            .apply { if (failure == null && engine.currentConfig.speakCaptions) addAction(0, uiText(UiR.string.service_silence_voice_7c1e9), command(5, ACTION_SILENCE)) }
            .apply {
                if (tapThrough) addAction(0, uiText(UiR.string.service_restore_controls_c97dd), command(2, ACTION_CENTER))
                else addAction(0, if (overlayVisible) uiText(UiR.string.service_hide_bubble_658d0) else uiText(UiR.string.service_show_bubble_9a42e), command(4, ACTION_VISIBILITY))
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    companion object {
        private val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = runningState
        fun stopCaptions(context: Context) {
            context.startService(Intent(context, CaptionCaptureService::class.java).setAction(ACTION_STOP))
        }
        fun show(context: Context) {
            context.startService(Intent(context, CaptionCaptureService::class.java).setAction(ACTION_CENTER))
        }
        private const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = 800 // 50 ms capture cadence
        private const val CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 410
        private const val ACTION_START = "com.sal7one.transiber.caption.START"
        internal const val ACTION_STOP = "com.sal7one.transiber.caption.STOP"
        private const val ACTION_CENTER = "com.sal7one.transiber.caption.CENTER"
        internal const val ACTION_PAUSE = "com.sal7one.transiber.caption.PAUSE"
        internal const val ACTION_PAUSE_ONLY = "com.sal7one.transiber.caption.PAUSE_ONLY"
        internal const val ACTION_RESUME = "com.sal7one.transiber.caption.RESUME"
        private const val ACTION_SILENCE = "com.sal7one.transiber.caption.SILENCE"
        private const val ACTION_SETTINGS = "com.sal7one.transiber.caption.SETTINGS"
        private const val ACTION_VISIBILITY = "com.sal7one.transiber.caption.VISIBILITY"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_PROJECTION_RESULT = "projection_result"
        fun start(context: Context, source: CaptionSource, projectionResult: Intent?) {
            context.stopService(Intent(context, com.sal7one.transiber.reading.ReadingOverlayService::class.java))
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, CaptionCaptureService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_SOURCE, source).putExtra(EXTRA_PROJECTION_RESULT, projectionResult))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, CaptionCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
