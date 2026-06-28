package com.nsn8.vued.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.ambient.AmbientProcessor
import com.nsn8.vued.audio.CapturePipeline
import com.nsn8.vued.capture.AndroidMicCapture
import com.nsn8.vued.capture.MicArrayConfig
import com.nsn8.vued.capture.PROFILE_UMA8
import com.nsn8.vued.capture.Uma8Capture
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10

/**
 * Always-on foreground service that streams the configured UMA mic array, falling
 * back to Android microphone capture when no array is connected. The service runs
 * the [CapturePipeline] and continuously writes 16 kHz mono M4A segments to disk.
 * This is the Phase-1 ambient-capture foundation; mode/meeting logic and upload
 * land in later phases.
 */
class RecordingService : Service() {

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ambientJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        startForegroundNotification()
        acquireWakeLock()
        running = true
        RecorderState.reset()
        RecorderState.update { it.copy(running = true) }

        captureThread = Thread({ captureLoop() }, "uma8-capture").also { it.start() }
        return START_STICKY
    }

    private fun captureLoop() {
        val segmentsDir = File(getExternalFilesDir(null), "segments")
        // Resolve the array profile before building the pipeline so the downmixer
        // sees the correct channel count. Manual selection overrides auto-detect;
        // when no array is plugged in, fall back to Android's built-in microphone.
        val override = MicArrayConfig.selection(this).toProfile()
        val capture = Uma8Capture(this, override)
        val connectedArray = capture.findDevice()
        val useAndroidMic = connectedArray == null
        val profile = if (useAndroidMic) {
            override ?: PROFILE_UMA8
        } else {
            capture.resolveProfile() ?: override ?: PROFILE_UMA8
        }
        Log.i(
            TAG,
            if (useAndroidMic) {
                "capture profile=Android mic sampleRate=${AndroidMicCapture.SAMPLE_RATE_HZ}"
            } else {
                "capture profile=${profile.label} channels=${profile.outChannels}"
            }
        )
        val pipeline = CapturePipeline(segmentsDir, profile.outChannels)
        MeetingController.attach(pipeline.rollingBuffer)
        AmbientFlusher.attach(pipeline.rollingBuffer)
        // Drain any backlog left by a previous run (offline/crash) as soon as we're up.
        ambientScope.launch {
            MeetingController.retryPendingExports(applicationContext)
            runCatching { OutboundQueue.drain(applicationContext) }
            runCatching { AmbientProcessor.processOnce(applicationContext) }
        }
        ambientJob = ambientScope.launch {
            while (isActive) {
                delay(AmbientFlusher.INTERVAL_MS)
                MeetingController.retryPendingExports(applicationContext)
                runCatching { AmbientFlusher.flushOnce(this@RecordingService) }
                runCatching { AmbientProcessor.processOnce(this@RecordingService) }
            }
        }
        var lastPublish = 0L

        try {
            if (useAndroidMic) {
                lastPublish = streamAndroidMic(pipeline, lastPublish)
            } else {
                try {
                    capture.streamPcm(
                        onPcm = { buffer, length ->
                            pipeline.process(buffer, length)
                            lastPublish = publishStateIfDue(pipeline, lastPublish)
                        },
                        shouldContinue = { running },
                    )
                } catch (error: Throwable) {
                    if (!running) throw error
                    Log.w(TAG, "UMA capture ended; falling back to Android mic: ${error.message}", error)
                    RecorderState.update { it.copy(error = "UMA unavailable; using Android mic") }
                    lastPublish = streamAndroidMic(pipeline, lastPublish)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Capture loop ended: ${error.message}", error)
            RecorderState.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
        } finally {
            ambientJob?.cancel()
            AmbientFlusher.detach()
            MeetingController.detach()
            pipeline.close()
            RecorderState.update {
                it.copy(running = false, lastSegment = pipeline.lastSegmentPath, segmentCount = pipeline.segmentCount)
            }
            if (running) {
                // Stream died on its own (e.g. UMA-8 unplugged); tear the service down.
                running = false
                stopSelf()
            }
        }
    }

    private fun streamAndroidMic(pipeline: CapturePipeline, initialLastPublish: Long): Long {
        var lastPublish = initialLastPublish
        AndroidMicCapture().streamPcm(
            onPcm = { samples, length ->
                pipeline.process16kMono(samples, length)
                lastPublish = publishStateIfDue(pipeline, lastPublish)
            },
            shouldContinue = { running },
        )
        return lastPublish
    }

    private fun publishStateIfDue(pipeline: CapturePipeline, lastPublish: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_INTERVAL_MS) return lastPublish
        val peak = pipeline.peak
        val db = if (peak > 0f) 20f * log10(peak) else Float.NEGATIVE_INFINITY
        RecorderState.update {
            it.copy(
                segmentCount = pipeline.segmentCount,
                lastSegment = pipeline.lastSegmentPath,
                peakDb = db,
            )
        }
        return now
    }

    override fun onDestroy() {
        running = false
        captureThread?.join(2_000)
        captureThread = null
        releaseWakeLock()
        RecorderState.update { it.copy(running = false) }
        super.onDestroy()
    }

    /**
     * Partial wake lock: keeps the CPU running so USB capture + uploads continue
     * with the screen off / under Doze. Held for the life of the service (the kiosk
     * is plugged in); released on stop. The foreground service keeps us alive; this
     * keeps us *awake*.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vued:capture").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ambient audio capture" }
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Vued is recording")
            .setContentText("Ambient capture from UMA-8")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.nsn8.vued.action.STOP_RECORDING"
        private const val CHANNEL_ID = "vued_recording"
        private const val NOTIF_ID = 1001
        private const val PUBLISH_INTERVAL_MS = 300L
        private const val TAG = "VuedRecordingService"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
