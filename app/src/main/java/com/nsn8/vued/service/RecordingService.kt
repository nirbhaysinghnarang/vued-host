package com.nsn8.vued.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.AmplitudeTracker
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.VuedConfig
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.ambient.AmbientProcessor
import com.nsn8.vued.audio.CapturePipeline
import com.nsn8.vued.capture.AndroidMicCapture
import com.nsn8.vued.capture.MicArrayProfile
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
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.log10

/**
 * Always-on foreground service that streams the configured UMA mic array. If
 * [VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK] is enabled, Android microphone capture
 * backs up unavailable arrays; otherwise UMA availability is required.
 */
internal fun canStartRecordingCapture(
    allowBuiltInMicFallback: Boolean,
    umaConnected: Boolean,
    usbPermissionGranted: Boolean,
): Boolean = allowBuiltInMicFallback || (umaConnected && usbPermissionGranted)

class RecordingService : Service() {

    @Volatile
    private var running = false
    private var activeCaptureSessionId: Long? = null
    private var captureThread: Thread? = null
    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ambientJob: Job? = null
    private var captureWatchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUsbPermissionRequestKey: String? = null
    private var lastUsbPermissionRequestMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            activeCaptureSessionId?.let(captureSessions::invalidateIfActive)
            RecorderState.markCaptureStoppedByUser()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        if (!isCaptureStartEligible(this)) {
            RecorderState.markMicDisconnected(captureWasRunning = false)
            DiagnosticsLogger.warn("recording_start_rejected", mapOf("reason" to "mic_disconnected"))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            startForegroundNotification()
        } catch (error: SecurityException) {
            if (!VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK && !hasAuthorizedUma(this)) {
                RecorderState.markMicDisconnected(captureWasRunning = false)
            } else {
                RecorderState.update {
                    it.copy(
                        running = false,
                        captureReady = false,
                        error = error.message ?: "Unable to start recording",
                    )
                }
            }
            DiagnosticsLogger.warn(
                "recording_foreground_start_rejected",
                mapOf("message" to (error.message ?: "")),
                error,
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        val captureSessionId = captureSessions.begin()
        activeCaptureSessionId = captureSessionId
        val initialized = captureSessions.runIfActive(captureSessionId) {
            running = true
            RecorderState.reset()
            RecorderState.update { it.copy(running = true) }
        }
        if (!initialized) {
            running = false
            releaseWakeLock()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        captureThread = Thread(
            { captureLoop(captureSessionId) },
            "uma8-capture",
        ).also { it.start() }
        return START_STICKY
    }

    private fun captureLoop(captureSessionId: Long) {
        val segmentsDir = File(getExternalFilesDir(null), "segments")
        // Manual selection overrides auto-detect. Keep a single rolling buffer alive
        // while the physical capture source changes underneath it.
        val override = MicArrayConfig.selection(this).toProfile()
        val capture = Uma8Capture(this, override)
        val allowBuiltInMicFallback = VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK
        val profile = readyUmaProfile(capture) ?: override ?: PROFILE_UMA8
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
        var nextUmaAttemptMs = 0L
        var shouldStopSelf = false
        var lastStaleReportMs = 0L
        captureWatchdogJob = ambientScope.launch {
            while (isActive) {
                delay(CAPTURE_WATCHDOG_INTERVAL_MS)
                val status = RecorderState.state.value
                val lastAudioMs = pipeline.lastAudioMs
                val ageMs = if (lastAudioMs > 0L) System.currentTimeMillis() - lastAudioMs else Long.MAX_VALUE
                if (
                    running &&
                    captureSessions.isActive(captureSessionId) &&
                    status.captureReady &&
                    ageMs > RecorderState.CAPTURE_STALE_MS
                ) {
                    captureSessions.runIfActive(captureSessionId) {
                        RecorderState.markMicDisconnected()
                        AmplitudeTracker.track(
                            "mic_disconnected",
                            mapOf("reason" to "capture_stale", "ageMs" to ageMs),
                        )
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStaleReportMs >= CAPTURE_STALE_LOG_INTERVAL_MS) {
                            lastStaleReportMs = now
                            DiagnosticsLogger.warn("capture_stale", mapOf("ageMs" to ageMs))
                        }
                    }
                }
            }
        }

        try {
            while (running && captureSessions.isActive(captureSessionId)) {
                val umaProfile = if (SystemClock.elapsedRealtime() >= nextUmaAttemptMs) {
                    readyUmaProfile(capture)
                } else {
                    null
                }

                if (umaProfile != null) {
                    try {
                        pipeline.configureInputChannels(umaProfile.outChannels)
                        Log.i(TAG, "capture profile=${umaProfile.label} channels=${umaProfile.outChannels}")
                        DiagnosticsLogger.info("capture_profile_selected", mapOf(
                            "profile" to umaProfile.label,
                            "channels" to umaProfile.outChannels,
                        ))
                        capture.streamPcm(
                            onPcm = { buffer, length ->
                                if (captureSessions.isActive(captureSessionId)) {
                                    pipeline.process(buffer, length)
                                    publishCaptureReadyIfNeeded(
                                        captureSessionId,
                                        "uma",
                                        pipeline.lastAudioMs,
                                    )
                                    lastPublish = publishStateIfDue(
                                        captureSessionId,
                                        pipeline,
                                        lastPublish,
                                    )
                                }
                            },
                            shouldContinue = {
                                running && captureSessions.isActive(captureSessionId)
                            },
                        )
                        nextUmaAttemptMs = 0L
                    } catch (error: Throwable) {
                        if (!running || !captureSessions.isActive(captureSessionId)) throw error
                        if (!allowBuiltInMicFallback) {
                            handleUmaUnavailableWithoutFallback(captureSessionId, pipeline, error)
                            captureSessions.runIfActive(captureSessionId) {
                                shouldStopSelf = true
                                running = false
                            }
                            break
                        }
                        Log.w(TAG, "UMA capture ended; falling back to Android mic: ${error.message}", error)
                        DiagnosticsLogger.warn("uma_capture_fallback", mapOf("message" to (error.message ?: "")), error)
                        captureSessions.runIfActive(captureSessionId) {
                            RecorderState.update {
                                it.copy(
                                    captureReady = false,
                                    error = "UMA unavailable; using Android mic",
                                )
                            }
                        }
                        nextUmaAttemptMs = SystemClock.elapsedRealtime() + UMA_RETRY_AFTER_FAILURE_MS
                    }
                } else {
                    requestUmaPermissionIfNeeded(capture)
                    if (!allowBuiltInMicFallback) {
                        handleUmaUnavailableWithoutFallback(captureSessionId, pipeline, null)
                        captureSessions.runIfActive(captureSessionId) {
                            shouldStopSelf = true
                            running = false
                        }
                        break
                    }
                    Log.i(TAG, "capture profile=Android mic sampleRate=${AndroidMicCapture.SAMPLE_RATE_HZ}")
                    DiagnosticsLogger.info("capture_profile_selected", mapOf(
                        "profile" to "android_mic",
                        "sampleRate" to AndroidMicCapture.SAMPLE_RATE_HZ,
                    ))
                    captureSessions.runIfActive(captureSessionId) {
                        RecorderState.update {
                            it.copy(captureReady = false, micDisconnected = false)
                        }
                    }
                    lastPublish = streamAndroidMic(
                        captureSessionId = captureSessionId,
                        pipeline = pipeline,
                        initialLastPublish = lastPublish,
                        onReady = {
                            publishCaptureReadyIfNeeded(
                                captureSessionId,
                                "android_mic",
                                pipeline.lastAudioMs,
                            )
                        },
                        shouldContinue = {
                            requestUmaPermissionIfNeeded(capture)
                            running &&
                                captureSessions.isActive(captureSessionId) &&
                                !shouldAttemptUmaCapture(capture, nextUmaAttemptMs)
                        },
                    )
                }
            }
        } catch (error: Throwable) {
            captureSessions.runIfActive(captureSessionId) {
                Log.e(TAG, "Capture loop ended: ${error.message}", error)
                DiagnosticsLogger.error("capture_loop_failed", throwable = error)
                RecorderState.update {
                    it.copy(
                        captureReady = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        } finally {
            ambientJob?.cancel()
            captureWatchdogJob?.cancel()
            AmbientFlusher.detach()
            MeetingController.detach()
            pipeline.close()
            var stopServiceForEndedStream = false
            captureSessions.finishIfActive(captureSessionId) {
                stopServiceForEndedStream = running || shouldStopSelf
                running = false
                RecorderState.update {
                    it.copy(
                        running = false,
                        captureReady = false,
                        lastSegment = pipeline.lastSegmentPath,
                        lastAudioMs = pipeline.lastAudioMs,
                        segmentCount = pipeline.segmentCount,
                    )
                }
            }
            if (stopServiceForEndedStream) {
                // Stream died on its own (e.g. UMA-8 unplugged); tear the service down.
                stopSelf()
            }
        }
    }

    private fun readyUmaProfile(capture: Uma8Capture): MicArrayProfile? {
        val device = capture.findDevice() ?: return null
        if (!capture.hasPermission(device)) return null
        return capture.resolveProfile()
    }

    private fun handleUmaUnavailableWithoutFallback(
        captureSessionId: Long,
        pipeline: CapturePipeline,
        error: Throwable?,
    ) {
        val message = "Mic disconnected"
        Log.w(TAG, if (error == null) message else "$message: ${error.message}", error)
        DiagnosticsLogger.warn(
            "uma_capture_required_unavailable",
            mapOf("message" to (error?.message ?: "UMA mic unavailable")),
            error,
        )
        AmplitudeTracker.track(
            "mic_disconnected",
            mapOf("reason" to "uma_unavailable", "message" to (error?.message ?: "UMA mic unavailable")),
        )
        captureSessions.runIfActive(captureSessionId) {
            RecorderState.markMicDisconnected()
        }
        runCatching {
            runBlocking {
                if (MeetingController.active == null) {
                    AmbientFlusher.flushOnce(this@RecordingService)
                }
            }
        }.onFailure { flushError ->
            Log.w(TAG, "Failed to flush after UMA disconnect: ${flushError.message}", flushError)
            DiagnosticsLogger.warn(
                "uma_disconnect_flush_failed",
                mapOf("message" to (flushError.message ?: "")),
                flushError,
            )
        }
        pipeline.close()
    }

    private fun requestUmaPermissionIfNeeded(capture: Uma8Capture) {
        val device = capture.findDevice() ?: return
        if (capture.hasPermission(device)) return

        val now = SystemClock.elapsedRealtime()
        val deviceKey = "${device.vendorId}:${device.productId}:${device.deviceName}"
        if (
            deviceKey == lastUsbPermissionRequestKey &&
            now - lastUsbPermissionRequestMs < USB_PERMISSION_REQUEST_INTERVAL_MS
        ) {
            return
        }
        lastUsbPermissionRequestKey = deviceKey
        lastUsbPermissionRequestMs = now

        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        getSystemService(UsbManager::class.java).requestPermission(device, intent)
        Log.i(TAG, "requested USB permission for ${device.productName ?: device.deviceName}")
    }

    private fun shouldAttemptUmaCapture(capture: Uma8Capture, nextUmaAttemptMs: Long): Boolean {
        if (SystemClock.elapsedRealtime() < nextUmaAttemptMs) return false
        val device = capture.findDevice() ?: return false
        return capture.hasPermission(device)
    }

    private fun streamAndroidMic(
        captureSessionId: Long,
        pipeline: CapturePipeline,
        initialLastPublish: Long,
        onReady: () -> Unit,
        shouldContinue: () -> Boolean,
    ): Long {
        var lastPublish = initialLastPublish
        AndroidMicCapture().streamPcm(
            onPcm = { samples, length ->
                if (captureSessions.isActive(captureSessionId)) {
                    pipeline.process16kMono(samples, length)
                    onReady()
                    lastPublish = publishStateIfDue(
                        captureSessionId,
                        pipeline,
                        lastPublish,
                    )
                }
            },
            shouldContinue = shouldContinue,
        )
        return lastPublish
    }

    private fun publishCaptureReadyIfNeeded(
        captureSessionId: Long,
        source: String,
        lastAudioMs: Long,
    ) {
        if (RecorderState.state.value.hasFreshAudio()) return
        captureSessions.runIfActive(captureSessionId) {
            if (RecorderState.state.value.hasFreshAudio()) return@runIfActive
            DiagnosticsLogger.info("capture_ready", mapOf("source" to source))
            AmplitudeTracker.track("mic_connected", mapOf("source" to source))
            RecorderState.update {
                it.copy(
                    running = true,
                    captureReady = true,
                    micDisconnected = false,
                    disconnectedAtMs = 0L,
                    resumeOnReconnect = false,
                    lastAudioMs = lastAudioMs,
                    error = null,
                )
            }
        }
    }

    private fun publishStateIfDue(
        captureSessionId: Long,
        pipeline: CapturePipeline,
        lastPublish: Long,
    ): Long {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_INTERVAL_MS) return lastPublish
        var nextPublish = lastPublish
        captureSessions.runIfActive(captureSessionId) {
            val peak = pipeline.peak
            val db = if (peak > 0f) 20f * log10(peak) else Float.NEGATIVE_INFINITY
            RecorderState.update {
                it.copy(
                    segmentCount = pipeline.segmentCount,
                    lastSegment = pipeline.lastSegmentPath,
                    lastAudioMs = pipeline.lastAudioMs,
                    peakDb = db,
                )
            }
            nextPublish = now
        }
        return nextPublish
    }

    override fun onDestroy() {
        running = false
        val invalidatedActiveSession = activeCaptureSessionId
            ?.let(captureSessions::invalidateIfActive)
            ?: false
        captureThread?.join(2_000)
        captureThread = null
        releaseWakeLock()
        if (invalidatedActiveSession) {
            RecorderState.update { it.copy(running = false, captureReady = false) }
        }
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
            val serviceType = if (VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIF_ID, notification, serviceType)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.nsn8.vued.action.STOP_RECORDING"
        private const val ACTION_USB_PERMISSION = "com.nsn8.vued.USB_PERMISSION"
        private const val CHANNEL_ID = "vued_recording"
        private const val NOTIF_ID = 1001
        private const val PUBLISH_INTERVAL_MS = 300L
        private const val CAPTURE_WATCHDOG_INTERVAL_MS = 1_000L
        private const val CAPTURE_STALE_LOG_INTERVAL_MS = 30_000L
        private const val UMA_RETRY_AFTER_FAILURE_MS = 5_000L
        private const val USB_PERMISSION_REQUEST_INTERVAL_MS = 30_000L
        private const val TAG = "VuedRecordingService"
        private val captureSessions = CaptureSessionGate()

        fun start(context: Context): Boolean {
            if (!isCaptureStartEligible(context)) {
                RecorderState.markMicDisconnected(captureWasRunning = false)
                return false
            }
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
            return true
        }

        fun stop(context: Context) {
            // Publish mute intent before the service command is delivered so a USB
            // detach immediately after a tap cannot be mistaken for active capture.
            captureSessions.invalidateCurrent()
            RecorderState.markCaptureStoppedByUser()
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }

        internal fun isCaptureStartEligible(context: Context): Boolean {
            val allowFallback = VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK
            if (allowFallback) {
                return canStartRecordingCapture(
                    allowBuiltInMicFallback = true,
                    umaConnected = false,
                    usbPermissionGranted = false,
                )
            }
            val capture = Uma8Capture(context.applicationContext)
            val device = capture.findDevice()
            return canStartRecordingCapture(
                allowBuiltInMicFallback = false,
                umaConnected = device != null,
                usbPermissionGranted = device?.let(capture::hasPermission) == true,
            )
        }

        private fun hasAuthorizedUma(context: Context): Boolean {
            val capture = Uma8Capture(context.applicationContext)
            val device = capture.findDevice() ?: return false
            return capture.hasPermission(device)
        }
    }
}
