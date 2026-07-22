package com.nsn8.vued

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.ambient.AmbientProcessor
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.capture.MicArrayConfig
import com.nsn8.vued.capture.MicArraySelection
import com.nsn8.vued.capture.Uma8Capture
import com.nsn8.vued.crypto.AmbientDecryptor
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.ui.ProdSpeakerEnrollmentDialog
import com.nsn8.vued.ui.RoomPickerDialog
import com.nsn8.vued.ui.SpeakerEnrollmentDialog
import com.nsn8.vued.service.RecorderState
import com.nsn8.vued.service.RecordingService
import com.nsn8.vued.status.RoomMicCommand
import com.nsn8.vued.status.RoomMicStatus
import com.nsn8.vued.status.RoomMicStatusBroadcast
import com.nsn8.vued.status.RoomMicStatusBroadcasts
import com.nsn8.vued.status.isMicCommandConfirmed
import com.nsn8.vued.status.isMicCommandRejectedAsDisconnected
import com.nsn8.vued.ui.LoginScreen
import com.nsn8.vued.ui.theme.VuedTheme
import com.nsn8.vued.update.SelfUpdateManager
import io.github.jan.supabase.auth.status.SessionStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.nsn8.vued.USB_PERMISSION"
private const val USB_PERMISSION_REQUEST_INTERVAL_MS = 30_000L
private const val RECONNECT_MEETING_GRACE_MS = 60_000L
private const val RECORDER_RECONNECT_RETRY_MS = 250L
private const val RECORDER_RECONNECT_TIMEOUT_MS = 60_000L
private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
private const val MIC_STATUS_OFFLINE_AFTER_MS = 60_000L
private const val MIC_COMMAND_CONFIRM_TIMEOUT_MS = 15_000L
private const val MIC_DRAWER_ANIMATION_MS = 220
private const val TAG = "VuedMainActivity"
private val HOST_UI_MODE = HostUiMode.PROD

private enum class HostUiMode { DEV, PROD }

private data class WifiStatus(
    val connected: Boolean,
    val ssid: String?,
)

private data class BatteryStatus(
    val levelPercent: Int?,
    val charging: Boolean,
    val plugged: Boolean,
    val powerSource: String?,
)

private data class MicConnectionUi(
    val label: String,
    val color: Color,
    val contentDescription: String,
)

private val VuedBackground = Color(0xFFFFFFFF)
private val VuedSurface = Color(0xFFF8F9FB)
private val VuedSurfaceRaised = Color(0xFFFFFFFF)
private val VuedHairline = Color(0xFFD6DDE6)
private val VuedTextPrimary = Color(0xFF0B0D12)
private val VuedTextSecondary = Color(0xFF2F3744)
private val VuedTextTertiary = Color(0xFF5B6573)
private val VuedSuccess = Color(0xFF16764F)
private val VuedChargingBolt = Color(0xFFFFC107)
private val VuedDanger = Color(0xFFB42318)
private val VuedIdleRing = Color(0xFFE5EAF0)

class MainActivity : ComponentActivity() {
    private var pendingKioskAfterUsbPermission = false
    private var pendingRecorderStartAfterUsbPermission = false
    private var pendingRecorderStartRequiresResumeState = false
    private var lastUsbPermissionRequestKey: String? = null
    private var lastUsbPermissionRequestMs: Long = 0
    private var kioskManuallyEscaped = false
    private var recorderReconnectJob: Job? = null
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val attachedDevice = intent.usbDeviceExtra()
                    if (attachedDevice.matchesUmaSafely()) {
                        AmplitudeTracker.track("mic_connected", usbDeviceData(attachedDevice) + mapOf("source" to "usb_attached"))
                    }
                    dismissMicDisconnectedIfUmaDetected(attachedDevice, trigger = "usb_attached")
                    logKioskUsbEvent(
                        "kiosk_usb_attach_received",
                        attachedDevice,
                        mapOf("matchesUma" to attachedDevice.matchesUmaSafely()),
                    )
                    if (attachedDevice.matchesUmaSafely()) {
                        scheduleRecorderReconnect(attachedDevice)
                    }
                    requestUmaPermissionForKioskRecovery(trigger = "usb_attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = intent.usbDeviceExtra()
                    if (detachedDevice.matchesUmaSafely()) {
                        RecorderState.markMicDisconnected()
                        AmplitudeTracker.track(
                            "mic_disconnected",
                            usbDeviceData(detachedDevice) + mapOf("reason" to "usb_detached"),
                        )
                        logKioskUsbEvent("kiosk_usb_detach_received", detachedDevice)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDeviceExtra()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val shouldStartRecorder = pendingRecorderStartAfterUsbPermission
                    val requiresResumeState = pendingRecorderStartRequiresResumeState
                    logKioskUsbEvent(
                        "kiosk_usb_permission_result",
                        device,
                        mapOf(
                            "granted" to granted,
                            "willRelock" to isDeviceOwner(this@MainActivity),
                        ),
                    )
                    lastUsbPermissionRequestKey = null
                    pendingKioskAfterUsbPermission = false
                    pendingRecorderStartAfterUsbPermission = false
                    pendingRecorderStartRequiresResumeState = false
                    if (granted) {
                        dismissMicDisconnectedIfUmaDetected(device, trigger = "usb_permission_granted")
                    }
                    if (granted && shouldStartRecorder) {
                        if (requiresResumeState) {
                            resumeRecorderAfterUmaReconnect("usb_permission_granted", device)
                        } else {
                            RecordingService.start(this@MainActivity)
                        }
                    } else if (granted) {
                        resumeRecorderAfterUmaReconnect("usb_permission_granted", device)
                    }
                    if (isDeviceOwner(this@MainActivity)) {
                        val result = startKiosk(this@MainActivity)
                        logKioskUsbEvent("kiosk_usb_relock_after_permission", device, mapOf("result" to result))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on during in-room operation so capture/upload remains
        // responsive while the recorder is foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        hideStatusBar()
        setContent {
            VuedTheme(desktopTheme = HOST_UI_MODE == HostUiMode.PROD) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate()
                }
            }
        }
        handleUsbAttachIntent(intent, trigger = "activity_create_intent")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent, trigger = "activity_new_intent")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(usbPermissionReceiver)
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        if (isDeviceOwner(this)) {
            logKioskUsbEvent("kiosk_resume_device_owner", data = mapOf("locked" to isKioskLocked(this)))
            if (kioskManuallyEscaped) {
                logKioskUsbEvent("kiosk_resume_lock_skipped_after_escape")
                return
            }
            if (!requestUmaPermissionForKioskRecovery(trigger = "activity_resume")) {
                val result = startKiosk(this)
                logKioskUsbEvent("kiosk_resume_lock_started", data = mapOf("result" to result))
            }
        }
    }

    fun markKioskManuallyEscaped() {
        kioskManuallyEscaped = true
    }

    fun requestUmaPermissionForKioskRecovery(trigger: String = "manual"): Boolean {
        val requested = requestUmaPermission(this, unlockKioskForDialog = true)
        logKioskUsbEvent(
            "kiosk_usb_permission_recovery_checked",
            Uma8Capture(this).findDevice(),
            mapOf(
                "trigger" to trigger,
                "requested" to requested,
                "locked" to isKioskLocked(this),
            ),
        )
        if (requested) {
            pendingKioskAfterUsbPermission = true
        }
        return requested
    }

    fun requestUmaPermissionForRecorderStart(
        trigger: String = "manual",
        requireResumeState: Boolean = false,
    ): Boolean {
        val requested = requestUmaPermission(this, unlockKioskForDialog = true)
        logKioskUsbEvent(
            "recorder_usb_permission_checked",
            Uma8Capture(this).findDevice(),
            mapOf(
                "trigger" to trigger,
                "requested" to requested,
                "requireResumeState" to requireResumeState,
            ),
        )
        if (requested) {
            pendingRecorderStartAfterUsbPermission = true
            pendingRecorderStartRequiresResumeState = requireResumeState
        }
        return requested
    }

    private fun resumeRecorderAfterUmaReconnect(trigger: String, device: UsbDevice?): Boolean {
        if (!device.matchesUmaSafely()) return false
        val status = RecorderState.state.value
        val disconnectedAtMs = status.disconnectedAtMs
        if (status.running || disconnectedAtMs <= 0L || !status.resumeOnReconnect) return false
        val elapsedMs = System.currentTimeMillis() - disconnectedAtMs

        val capture = Uma8Capture(this)
        val connected = capture.findDevice() ?: return false
        if (!capture.hasPermission(connected)) {
            return requestUmaPermissionForRecorderStart(
                trigger = trigger,
                requireResumeState = true,
            )
        }

        logKioskUsbEvent(
            "recorder_usb_reconnect_autoresume",
            connected,
            mapOf("trigger" to trigger, "elapsedMs" to elapsedMs),
        )
        RecordingService.start(this)
        return true
    }

    private fun scheduleRecorderReconnect(device: UsbDevice?) {
        recorderReconnectJob?.cancel()
        recorderReconnectJob = lifecycleScope.launch {
            val attachedAtMs = System.currentTimeMillis()
            val deadlineMs = SystemClock.elapsedRealtime() + RECORDER_RECONNECT_TIMEOUT_MS

            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val status = RecorderState.state.value

                // The existing capture recovered across the short USB reset.
                if (status.running && status.hasFreshAudio() && status.lastAudioMs >= attachedAtMs) {
                    logKioskUsbEvent(
                        "recorder_usb_reconnect_recovered",
                        device,
                        mapOf("elapsedMs" to (System.currentTimeMillis() - attachedAtMs)),
                    )
                    return@launch
                }

                // A failed capture stops asynchronously. Retry once its service has
                // published the stopped state instead of losing the early attach event.
                if (!status.running && status.disconnectedAtMs > 0L && status.resumeOnReconnect) {
                    if (resumeRecorderAfterUmaReconnect("usb_attach_retry", device)) {
                        return@launch
                    }
                }

                delay(RECORDER_RECONNECT_RETRY_MS)
            }

            logKioskUsbEvent("recorder_usb_reconnect_retry_expired", device)
        }
    }

    private fun requestUmaPermission(context: Context, unlockKioskForDialog: Boolean): Boolean {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val device = Uma8Capture(context).findDevice()
        if (device == null) {
            logKioskUsbEvent(
                "kiosk_usb_permission_request_skipped",
                data = mapOf("reason" to "no_uma_device", "unlockKioskForDialog" to unlockKioskForDialog),
            )
            return false
        }
        dismissMicDisconnectedIfUmaDetected(device, trigger = "usb_device_list")
        if (usbManager.hasPermission(device)) {
            logKioskUsbEvent(
                "kiosk_usb_permission_request_skipped",
                device,
                mapOf("reason" to "already_authorized", "unlockKioskForDialog" to unlockKioskForDialog),
            )
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val deviceKey = "${device.vendorId}:${device.productId}:${device.deviceName}"
        if (
            pendingKioskAfterUsbPermission &&
            deviceKey == lastUsbPermissionRequestKey &&
            now - lastUsbPermissionRequestMs < USB_PERMISSION_REQUEST_INTERVAL_MS
        ) {
            logKioskUsbEvent(
                "kiosk_usb_permission_request_debounced",
                device,
                mapOf("elapsedMs" to (now - lastUsbPermissionRequestMs)),
            )
            return true
        }
        lastUsbPermissionRequestKey = deviceKey
        lastUsbPermissionRequestMs = now

        if (unlockKioskForDialog && context is Activity && isKioskLocked(context)) {
            val result = stopKiosk(context)
            logKioskUsbEvent("kiosk_usb_unlock_for_permission_dialog", device, mapOf("result" to result))
        } else {
            logKioskUsbEvent(
                "kiosk_usb_unlock_not_needed_for_permission_dialog",
                device,
                mapOf(
                    "unlockKioskForDialog" to unlockKioskForDialog,
                    "locked" to (context as? Activity)?.let(::isKioskLocked),
                ),
            )
        }

        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, intent)
        logKioskUsbEvent("kiosk_usb_permission_requested", device)
        return true
    }

    private fun handleUsbAttachIntent(intent: Intent?, trigger: String) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.usbDeviceExtra()
        dismissMicDisconnectedIfUmaDetected(device, trigger = trigger)
        logKioskUsbEvent("kiosk_usb_attach_activity_intent", device, mapOf("trigger" to trigger))
        if (device.matchesUmaSafely()) {
            scheduleRecorderReconnect(device)
        }
        requestUmaPermissionForKioskRecovery(trigger = trigger)
    }

    private fun logKioskUsbEvent(
        event: String,
        device: UsbDevice? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val eventData = usbDeviceData(device) + data
        Log.i(TAG, "$event $eventData")
        DiagnosticsLogger.info(event, eventData)
    }

    private fun dismissMicDisconnectedIfUmaDetected(device: UsbDevice?, trigger: String) {
        val umaDevice = device?.takeIf { it.matchesUmaSafely() } ?: return
        if (!Uma8Capture(this).hasPermission(umaDevice)) {
            logKioskUsbEvent(
                "kiosk_usb_detected_waiting_for_permission",
                umaDevice,
                mapOf("trigger" to trigger),
            )
            return
        }
        val status = RecorderState.state.value
        if (!status.micDisconnected) return
        RecorderState.markMicReconnected()
        logKioskUsbEvent("kiosk_usb_detected_dismissed_disconnect", umaDevice, mapOf("trigger" to trigger))
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private fun Intent.usbDeviceExtra(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

private fun usbDeviceData(device: UsbDevice?): Map<String, Any?> =
    if (device == null) {
        mapOf("devicePresent" to false)
    } else {
        mapOf(
            "devicePresent" to true,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "deviceName" to device.deviceName,
            "productName" to runCatching { device.productName }.getOrNull(),
            "manufacturerName" to runCatching { device.manufacturerName }.getOrNull(),
            "matchesUma" to device.matchesUmaSafely(),
        )
    }

private fun UsbDevice?.matchesUmaSafely(): Boolean =
    this?.let { runCatching { Uma8Capture.isMicArrayDevice(it) }.getOrDefault(false) } ?: false

@Composable
private fun AuthGate() {
    val context = LocalContext.current
    val authStatus by VuedAuth.sessionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var loginWifiStatus by remember { mutableStateOf(currentWifiStatus(context)) }
    var batteryStatus by remember { mutableStateOf(currentBatteryStatus(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                batteryStatus = batteryStatusFromIntent(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    val loginConnectionButtons: @Composable () -> Unit = {
        WifiSettingsButton(
            status = loginWifiStatus,
            labelOverride = "Set up Wi-Fi",
            onClick = {
                openWifiSettings(context)
                loginWifiStatus = currentWifiStatus(context)
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (authStatus) {
            is SessionStatus.Authenticated -> {
                when (HOST_UI_MODE) {
                    HostUiMode.PROD -> ProdRecorderScreen()
                    HostUiMode.DEV -> {
                        DevRecorderScreen(
                            userEmail = VuedAuth.currentEmail(),
                            onSignOut = { scope.launch { VuedAuth.signOut() } },
                        )
                    }
                }
            }
            is SessionStatus.RefreshFailure -> LoginScreen(
                initialError = "Session expired, sign in again",
                wifiSettingsButton = loginConnectionButtons,
            )
            SessionStatus.Initializing -> LoadingScreen()
            is SessionStatus.NotAuthenticated -> LoginScreen(
                wifiSettingsButton = loginConnectionButtons,
            )
        }

        val batteryLevel = batteryStatus.levelPercent
        if (batteryLevel != null &&
            batteryLevel < LOW_BATTERY_THRESHOLD_PERCENT &&
            !batteryStatus.plugged
        ) {
            LowBatteryPowerBanner(levelPercent = batteryLevel)
        }
    }
}

@Composable
private fun LowBatteryPowerBanner(levelPercent: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            shape = RoundedCornerShape(14.dp),
            color = VuedDanger,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "!",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Battery too low",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Battery is at $levelPercent%. Plug in the tablet — this warning clears when power is connected.",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "…",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ProdRecorderScreen() {
    val context = LocalContext.current
    var roomName by remember { mutableStateOf(RoomConfig.roomName(context)) }
    var ambientUnlocked by remember { mutableStateOf(AmbientDecryptor.isUnlocked(context)) }

    if (!ambientUnlocked) {
        AmbientPassphraseOnboardingScreen(onUnlocked = { ambientUnlocked = true })
    } else if (roomName == null) {
        RoomOnboardingScreen(onRoomPicked = { roomName = it })
    } else {
        ProdRecorderMainScreen()
    }
}

@Composable
private fun AmbientPassphraseOnboardingScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var hasVault by remember { mutableStateOf<Boolean?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            hasVault = AmbientDecryptor.hasRemoteVault()
        } catch (e: Exception) {
            error = e.message ?: "Could not check encryption setup."
        } finally {
            loading = false
        }
    }

    val creating = hasVault == false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VuedBackground)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f),
            shape = RoundedCornerShape(8.dp),
            color = VuedSurfaceRaised,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, VuedHairline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (creating) "Create encryption passphrase" else "Enter encryption passphrase",
                    color = VuedTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    letterSpacing = 0.sp,
                )
                Text(
                    text = if (creating) {
                        "This tablet needs a passphrase before ambient processing can run."
                    } else {
                        "Unlock this tablet."
                    },
                    color = VuedTextTertiary,
                    fontSize = 15.sp,
                    letterSpacing = 0.sp,
                )
                if (loading) {
                    Text("Checking encryption setup...", color = VuedTextSecondary)
                } else {
                    PassphraseTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = "Enter your passphrase.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (creating) {
                        PassphraseTextField(
                            value = confirmPassphrase,
                            onValueChange = { confirmPassphrase = it },
                            label = "Confirm passphrase",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !busy && passphrase.isNotBlank() && (!creating || confirmPassphrase.isNotBlank()),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VuedTextPrimary,
                            contentColor = Color.White,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    if (creating) {
                                        if (passphrase != confirmPassphrase) {
                                            error = "Passphrases do not match."
                                            return@launch
                                        }
                                        AmbientDecryptor.provision(context, passphrase)
                                    } else {
                                        AmbientDecryptor.unlock(context, passphrase)
                                    }
                                    passphrase = ""
                                    confirmPassphrase = ""
                                    onUnlocked()
                                } catch (e: Throwable) {
                                    error = e.message ?: "Could not unlock encryption."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(
                            text = when {
                                busy -> if (creating) "Creating..." else "Unlocking..."
                                creating -> "Create passphrase"
                                else -> "Unlock"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PassphraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var passphraseVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (passphraseVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { passphraseVisible = !passphraseVisible }) {
                Text(if (passphraseVisible) "Hide" else "Show")
            }
        },
        singleLine = true,
        modifier = modifier,
    )
}

private fun suggestedRoomMicrophoneId(roomName: String): String {
    return roomName.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "tablet-room" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomOnboardingScreen(onRoomPicked: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roomNameBringIntoView = remember { BringIntoViewRequester() }
    val microphoneIdBringIntoView = remember { BringIntoViewRequester() }
    var rooms by remember { mutableStateOf<List<OrgApi.Room>>(emptyList()) }
    var orgId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var selectedRoomId by remember { mutableStateOf<String?>(null) }
    var roomDraftName by remember { mutableStateOf("") }
    var roomDraftMicrophoneId by remember { mutableStateOf("") }
    val selectedRoom = rooms.firstOrNull { it.id == selectedRoomId }
    val isCreatingDraft = roomDraftName.trim().isNotEmpty()
    val canSubmit = !creating &&
        orgId != null &&
        if (isCreatingDraft) {
            roomDraftMicrophoneId.trim().isNotEmpty()
        } else {
            selectedRoom != null
        }

    fun selectRoom(room: OrgApi.Room) {
        RoomConfig.set(
            context,
            room.id,
            room.displayName,
            orgId.orEmpty(),
            room.microphoneId,
        )
        onRoomPicked(room.displayName)
    }

    fun Modifier.keyboardFocused(requester: BringIntoViewRequester): Modifier {
        return bringIntoViewRequester(requester).onFocusEvent { focusState ->
            if (focusState.isFocused) {
                scope.launch {
                    delay(250)
                    requester.bringIntoView()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val org = OrgApi.getOrgs().firstOrNull()
            if (org == null) {
                error = "No organization found for this account."
            } else {
                orgId = org.id
                rooms = OrgApi.getRooms(org.id)
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not load rooms."
        } finally {
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VuedBackground)
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f),
            shape = RoundedCornerShape(8.dp),
            color = VuedSurfaceRaised,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, VuedHairline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Choose this tablet's room",
                    color = VuedTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    letterSpacing = 0.sp,
                )
                Text(
                    text = "This can only be set during onboarding.",
                    color = VuedTextTertiary,
                    fontSize = 15.sp,
                    letterSpacing = 0.sp,
                )
                when {
                    loading -> Text("Loading rooms...", color = VuedTextSecondary)
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else -> {
                        if (rooms.isEmpty()) {
                            Text(
                                text = "No rooms found. Create one below to assign this tablet.",
                                color = VuedTextSecondary,
                                fontSize = 15.sp,
                                letterSpacing = 0.sp,
                            )
                        } else {
                            rooms.forEach { room ->
                                val selected = room.id == selectedRoomId
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = VuedTextPrimary,
                                    ),
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) VuedSuccess else VuedHairline,
                                    ),
                                    onClick = {
                                        selectedRoomId = room.id
                                        roomDraftName = ""
                                        roomDraftMicrophoneId = ""
                                        createError = null
                                    },
                                ) {
                                    Text(
                                        text = room.displayName,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        color = if (selected) VuedSuccess else VuedTextPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        letterSpacing = 0.sp,
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Create new room",
                                color = VuedTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                letterSpacing = 0.sp,
                            )
                            OutlinedTextField(
                                value = roomDraftName,
                                onValueChange = { next ->
                                    val previousSuggestion = suggestedRoomMicrophoneId(roomDraftName)
                                    selectedRoomId = null
                                    createError = null
                                    roomDraftName = next
                                    if (roomDraftMicrophoneId.isBlank() || roomDraftMicrophoneId == previousSuggestion) {
                                        roomDraftMicrophoneId = suggestedRoomMicrophoneId(next)
                                    }
                                },
                                label = { Text("Room name") },
                                enabled = !creating,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .keyboardFocused(roomNameBringIntoView),
                            )
                            OutlinedTextField(
                                value = roomDraftMicrophoneId,
                                onValueChange = {
                                    selectedRoomId = null
                                    createError = null
                                    roomDraftMicrophoneId = it.trim()
                                },
                                label = { Text("Microphone ID") },
                                enabled = !creating,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .keyboardFocused(microphoneIdBringIntoView),
                            )
                            createError?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.sp,
                                )
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canSubmit,
                                shape = RoundedCornerShape(8.dp),
                                onClick = {
                                    val currentOrgId = orgId ?: return@Button
                                    if (isCreatingDraft) {
                                        val displayName = roomDraftName.trim()
                                        val microphoneId = roomDraftMicrophoneId.trim()
                                        scope.launch {
                                            creating = true
                                            createError = null
                                            try {
                                                val created = OrgApi.createRoom(currentOrgId, displayName, microphoneId)
                                                rooms = (rooms.filter { it.id != created.id } + created)
                                                    .sortedBy { it.displayName.lowercase(Locale.US) }
                                                roomDraftName = ""
                                                roomDraftMicrophoneId = ""
                                                selectedRoomId = created.id
                                                selectRoom(created)
                                            } catch (e: Exception) {
                                                createError = e.message ?: "Could not create room."
                                            } finally {
                                                creating = false
                                            }
                                        }
                                    } else {
                                        selectedRoom?.let { selectRoom(it) }
                                    }
                                },
                            ) {
                                Text(
                                    text = when {
                                        creating -> "Creating..."
                                        isCreatingDraft -> "Create new room"
                                        else -> "Select"
                                    },
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    fontSize = 16.sp,
                                    letterSpacing = 0.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProdRecorderMainScreen() {
    val context = LocalContext.current
    val status by RecorderState.state.collectAsState()
    val scope = rememberCoroutineScope()
    var roomName by remember { mutableStateOf(RoomConfig.roomName(context).orEmpty()) }
    val assignedRoomId = remember { RoomConfig.roomId(context) }
    val assignedOrgId = remember { RoomConfig.orgId(context) }

    var hasAudio by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStartAfterRuntimePermission by remember { mutableStateOf(false) }
    var runtimePermissionRequestInFlight by remember { mutableStateOf(false) }

    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudio = granted
        runtimePermissionRequestInFlight = false
    }
    val setupPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants[Manifest.permission.RECORD_AUDIO]?.let { granted -> hasAudio = granted }
        requestUma8Permission(context)
    }

    var meetingActive by remember { mutableStateOf(MeetingController.active != null) }
    var segmentStartedAt by remember { mutableStateOf(MeetingController.active?.startMs ?: 0L) }
    var segmentBusy by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showEnroll by remember { mutableStateOf(false) }
    var showMicStatuses by remember { mutableStateOf(false) }
    var wifiStatus by remember { mutableStateOf(currentWifiStatus(context)) }
    var batteryStatus by remember { mutableStateOf(currentBatteryStatus(context)) }
    var micArrayPresent by remember { mutableStateOf(isUmaMicPresent(context)) }
    var currentTime by remember { mutableStateOf(formatCurrentTime()) }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var updateTitle by remember { mutableStateOf("Software update") }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateRunToken by remember { mutableStateOf(0) }
    var updateJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiStatus = currentWifiStatus(context)
            }

            override fun onLost(network: Network) {
                wifiStatus = currentWifiStatus(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                wifiStatus = currentWifiStatus(context)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                batteryStatus = batteryStatusFromIntent(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val wasPresent = micArrayPresent
                micArrayPresent = isUmaMicPresent(ctx)
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && wasPresent && !micArrayPresent) {
                    // Physical disconnect handling is owned by MainActivity's lifecycle
                    // receiver so it also works while recording is muted.
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(meetingActive, segmentStartedAt) {
        while (meetingActive) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = formatCurrentTime()
            kotlinx.coroutines.delay(1_000)
        }
    }

    val captureReady = status.running && status.hasFreshAudio()
    val micConnectionStatus = micConnectionUi(
        micArrayPresent = micArrayPresent,
        status = status,
    )

    LaunchedEffect(captureReady, status.running, status.micDisconnected) {
        micArrayPresent = isUmaMicPresent(context)
        if (!captureReady) {
            val active = MeetingController.active
            meetingActive = active != null
            segmentStartedAt = active?.startMs ?: 0L
            nowMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(assignedRoomId, assignedOrgId) {
        if (assignedRoomId.isNullOrBlank()) return@LaunchedEffect
        runCatching {
            val orgId = assignedOrgId?.takeIf { it.isNotBlank() }
                ?: OrgApi.getOrgs().firstOrNull()?.id
                ?: return@runCatching
            OrgApi.getRooms(orgId).firstOrNull { it.id == assignedRoomId }?.let { room ->
                if (room.displayName.isNotBlank() && room.displayName != roomName) {
                    RoomConfig.set(context, room.id, room.displayName, orgId, room.microphoneId)
                    roomName = room.displayName
                }
            }
        }
    }

    fun setupPermissionsToRequest(): Array<String> {
        val permissions = mutableListOf<String>()
        if (!hasAudio) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    fun requestSetupPermissions(): Boolean {
        val permissions = setupPermissionsToRequest()
        if (permissions.isEmpty()) {
            requestUma8Permission(context)
            return false
        }
        setupPermissionLauncher.launch(permissions)
        return true
    }

    fun requestCapturePermission(): Boolean {
        if (hasAudio) return false
        runtimePermissionRequestInFlight = true
        capturePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return true
    }

    fun startCapture() {
        if (requestCapturePermission()) {
            pendingStartAfterRuntimePermission = true
            return
        }

        val umaDevice = Uma8Capture(context).findDevice()
        if (!VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK && umaDevice == null) {
            RecorderState.markMicDisconnected(captureWasRunning = false)
            return
        }

        if (requestUma8PermissionForStart(context)) return
        RecordingService.start(context)
    }

    LaunchedEffect(Unit) {
        requestSetupPermissions()
    }

    LaunchedEffect(
        pendingStartAfterRuntimePermission,
        runtimePermissionRequestInFlight,
        hasAudio,
    ) {
        if (pendingStartAfterRuntimePermission && !runtimePermissionRequestInFlight && hasAudio) {
            pendingStartAfterRuntimePermission = false
            startCapture()
        }
    }

    LaunchedEffect(status.micDisconnected, status.disconnectedAtMs) {
        val disconnectMs = status.disconnectedAtMs
        if (!status.micDisconnected || disconnectMs <= 0L) return@LaunchedEffect

        val remainingMs = (disconnectMs + RECONNECT_MEETING_GRACE_MS - System.currentTimeMillis())
            .coerceAtLeast(0L)
        delay(remainingMs)

        val latest = RecorderState.state.value
        val stillSameDisconnect = latest.micDisconnected &&
            latest.disconnectedAtMs == disconnectMs
        if (!stillSameDisconnect) return@LaunchedEffect

        runCatching {
            if (MeetingController.active != null) {
                MeetingController.stopAsync(context, endMs = disconnectMs)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to finalize meeting after mic disconnect grace: ${error.message}", error)
            DiagnosticsLogger.warn(
                "meeting_disconnect_grace_finalize_failed",
                mapOf("disconnectMs" to disconnectMs),
                error,
            )
        }
        RecorderState.markMicDisconnected(disconnectedAtMs = disconnectMs)
        meetingActive = MeetingController.active != null
        segmentStartedAt = MeetingController.active?.startMs ?: 0L
        nowMs = System.currentTimeMillis()
        DiagnosticsLogger.info("mic_disconnect_grace_expired", mapOf("disconnectMs" to disconnectMs))
    }

    val elapsedSecs = if (meetingActive) {
        ((nowMs - segmentStartedAt) / 1000).coerceAtLeast(0)
    } else {
        0
    }
    val showMicDisconnected = !VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK && status.micDisconnected

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VuedBackground)
                .padding(horizontal = 36.dp, vertical = 20.dp),
        ) {
        if (roomName.isNotBlank()) {
            Text(
                text = roomName,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
                color = VuedTextTertiary.copy(alpha = 0.38f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.sp,
            )
        }

        AudioMuteButton(
            unmuted = captureReady,
            enabled = !status.running || status.captureReady,
            modifier = Modifier.align(Alignment.TopStart),
            onClick = {
                if (status.running) {
                    AmplitudeTracker.track("mute")
                    RecordingService.stop(context)
                } else {
                    AmplitudeTracker.track("unmute")
                    startCapture()
                }
            },
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WifiSettingsButton(
                status = wifiStatus,
                onClick = {
                    openWifiSettings(context)
                    wifiStatus = currentWifiStatus(context)
                },
            )
            MicConnectionBadge(status = micConnectionStatus)
            BatteryStatusBadge(status = batteryStatus)
            AddSpeakerButton(onClick = { showEnroll = true })
            if (VuedConfig.MODE == VuedConfig.Mode.DEV) {
                DebugExceptionButton()
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val buttonSize = minOf(maxHeight * 0.68f, maxWidth * 0.46f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 36.dp),
            ) {
                MeetingCircleButton(
                    meetingActive = meetingActive,
                    enabled = !segmentBusy && (captureReady || meetingActive),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-28).dp)
                        .size(buttonSize),
                    onClick = {
                        if (meetingActive) {
                            runCatching { MeetingController.stopAsync(context) }
                            meetingActive = MeetingController.active != null
                            segmentStartedAt = MeetingController.active?.startMs ?: 0L
                            nowMs = System.currentTimeMillis()
                        } else {
                            scope.launch {
                                segmentBusy = true
                                try {
                                    MeetingController.start(context, "Meeting")
                                    segmentStartedAt = MeetingController.active?.startMs
                                        ?: System.currentTimeMillis()
                                    nowMs = System.currentTimeMillis()
                                    meetingActive = true
                                } catch (_: Throwable) {
                                    meetingActive = MeetingController.active != null
                                    segmentStartedAt = MeetingController.active?.startMs ?: 0L
                                } finally {
                                    segmentBusy = false
                                }
                            }
                        }
                    },
                )

                Text(
                    text = when {
                        showMicDisconnected -> "Mic disconnected"
                        meetingActive -> formatSegmentTime(elapsedSecs)
                        else -> "     "
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                    color = if (showMicDisconnected) Color(0xFFB42318) else VuedTextTertiary.copy(alpha = 0.62f),
                    fontSize = if (showMicDisconnected) 34.sp else 52.sp,
                    fontWeight = if (showMicDisconnected) FontWeight.Medium else FontWeight.Thin,
                    letterSpacing = 0.sp,
                )
            }
        }

        Text(
            text = currentTime,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 2.dp),
            color = VuedTextTertiary.copy(alpha = 0.62f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.sp,
        )

        SelfUpdateButton(
            versionCode = BuildConfig.VERSION_CODE,
            busy = updateBusy,
            enabled = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(bottom = 2.dp),
            onClick = {
                if (updateBusy) {
                    return@SelfUpdateButton
                }

                AmplitudeTracker.track("self_update_button_pressed")
                updateDialogVisible = true
                updateBusy = true
                updateRunToken += 1
                val runToken = updateRunToken
                updateTitle = "Checking for updates"
                updateMessage = "Looking for a newer version. Current version: ${BuildConfig.VERSION_CODE}."
                updateJob = scope.launch {
                    try {
                        when (val result = SelfUpdateManager.installLatest(context) { message ->
                            scope.launch(Dispatchers.Main) {
                                if (runToken == updateRunToken) {
                                    updateTitle = updateTitleForProgress(message)
                                    updateMessage = message
                                }
                            }
                        }) {
                            is SelfUpdateManager.UpdateResult.Installing -> {
                                if (runToken == updateRunToken) {
                                    updateTitle = "Installing update"
                                    updateMessage = "Android is installing ${result.versionName} (version ${result.versionCode}). The app will reopen automatically when installation finishes."
                                }
                            }
                            is SelfUpdateManager.UpdateResult.UpToDate -> {
                                if (runToken == updateRunToken) {
                                    updateTitle = "You're up to date"
                                    updateMessage = "Current version: ${result.versionCode}. No newer update is available."
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) {
                            throw error
                        }
                        if (runToken == updateRunToken) {
                            updateTitle = "Update unavailable"
                            updateMessage = error.message ?: "Could not complete the update check."
                        }
                        DiagnosticsLogger.error("self_update_ui_failed", throwable = error, sentry = false)
                    } finally {
                        if (runToken == updateRunToken) {
                            updateBusy = false
                            updateJob = null
                        }
                    }
                }
            },
        )

            if (!showMicStatuses) {
                MicStatusEdgeSwipeDetector(
                    modifier = Modifier
                        .align(Alignment.CenterEnd),
                    onOpen = { showMicStatuses = true },
                )
            }
        }

        if (!showMicStatuses) {
            MicStatusPhysicalEdgeSwipeDetector(
                modifier = Modifier.align(Alignment.CenterEnd),
                onOpen = { showMicStatuses = true },
            )
        }
    }

    if (showEnroll) {
        ProdSpeakerEnrollmentDialog(onDismiss = { showEnroll = false })
    }
    if (showMicStatuses) {
        MicStatusesDrawer(
            initialOrgId = assignedOrgId,
            currentRoomId = assignedRoomId,
            onDismiss = { showMicStatuses = false },
        )
    }
    if (updateDialogVisible) {
        SelfUpdateDialog(
            title = updateTitle,
            busy = updateBusy,
            message = updateMessage ?: "",
            versionCode = BuildConfig.VERSION_CODE,
            onDismiss = {
                if (!updateBusy) updateDialogVisible = false
            },
        )
    }
}

@Composable
internal fun MicStatusPhysicalEdgeSwipeDetector(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
    Spacer(
        modifier = modifier
            .fillMaxHeight()
            .width(36.dp)
            .semantics { contentDescription = "Swipe left to open microphone statuses" }
            .pointerInput(onOpen, thresholdPx) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragDistance += dragAmount },
                    onDragEnd = {
                        if (dragDistance <= -thresholdPx) onOpen()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
    )
}

@Composable
private fun MicStatusEdgeSwipeDetector(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(onOpen, thresholdPx) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragDistance += dragAmount },
                    onDragEnd = {
                        if (dragDistance <= -thresholdPx) onOpen()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        val handleShape = RoundedCornerShape(topStart = 13.dp, bottomStart = 13.dp)
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(76.dp)
                .clip(handleShape)
                .background(VuedSurfaceRaised.copy(alpha = 0.96f))
                .border(BorderStroke(1.dp, VuedHairline), handleShape)
                .clickable(onClick = onOpen)
                .semantics { contentDescription = "Open microphone statuses" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .width(9.dp)
                    .height(18.dp),
            ) {
                val stroke = 2.2.dp.toPx()
                drawLine(
                    color = VuedTextTertiary,
                    start = Offset(size.width * 0.72f, size.height * 0.18f),
                    end = Offset(size.width * 0.28f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = VuedTextTertiary,
                    start = Offset(size.width * 0.28f, size.height * 0.5f),
                    end = Offset(size.width * 0.72f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private data class PendingMicCommand(
    val command: RoomMicCommand,
    val token: Long,
)

internal data class MicCommandError(
    val roomId: String,
    val message: String,
    val clearsWhenMicReconnects: Boolean = false,
)

internal fun micCommandFailureMessage(
    command: RoomMicCommand,
    status: String?,
    roomName: String,
): String? = if (isMicCommandRejectedAsDisconnected(command, status)) {
    "$roomName's microphone is disconnected."
} else {
    null
}

internal fun messageForDisconnectedApiFailure(
    command: RoomMicCommand,
    apiMessage: String?,
    roomName: String,
): String? = if (
    command == RoomMicCommand.UNMUTE &&
    apiMessage?.contains("microphone is disconnected", ignoreCase = true) == true
) {
    micCommandFailureMessage(
        command = command,
        status = RoomMicStatus.MIC_DISCONNECTED.apiValue,
        roomName = roomName,
    )
} else {
    null
}

internal fun shouldClearMicCommandError(error: MicCommandError, room: OrgApi.Room): Boolean =
    error.clearsWhenMicReconnects &&
        error.roomId == room.id &&
        room.status != RoomMicStatus.MIC_DISCONNECTED.apiValue

internal fun isMicDisconnectedStatus(status: String?): Boolean =
    status == RoomMicStatus.MIC_DISCONNECTED.apiValue

internal fun isMicOnline(room: OrgApi.Room, nowMs: Long): Boolean {
    val updatedAtMs = room.statusUpdatedAt?.times(1_000.0)?.toLong()
    return updatedAtMs != null &&
        nowMs - updatedAtMs <= MIC_STATUS_OFFLINE_AFTER_MS
}

internal fun shouldShowMicUnavailableIndicator(room: OrgApi.Room, nowMs: Long): Boolean =
    !isMicOnline(room, nowMs) || isMicDisconnectedStatus(room.status)

@Composable
private fun MeetingRecordingDot() {
    val transition = rememberInfiniteTransition(label = "meeting-recording-dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "meeting-recording-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(dotAlpha)
            .background(VuedDanger, CircleShape)
            .semantics { contentDescription = "Meeting recording" },
    )
}

internal fun visibleMicRooms(
    rooms: List<OrgApi.Room>,
    currentRoomId: String?,
): List<OrgApi.Room> = rooms.filter { room ->
    room.status != null && room.id != currentRoomId
}

private fun isNewerMicStatus(existingTimestamp: Double?, candidateTimestamp: Double?): Boolean =
    when {
        existingTimestamp == null -> true
        candidateTimestamp == null -> false
        else -> candidateTimestamp > existingTimestamp
    }

internal fun mergeMicStatusSnapshot(
    rooms: List<OrgApi.Room>,
    snapshot: List<OrgApi.Room>,
    currentRoomId: String?,
): List<OrgApi.Room> = visibleMicRooms(snapshot, currentRoomId).map { candidate ->
    val existing = rooms.firstOrNull { it.id == candidate.id }
    if (existing != null && !isNewerMicStatus(existing.statusUpdatedAt, candidate.statusUpdatedAt)) {
        candidate.copy(
            status = existing.status,
            statusUpdatedAt = existing.statusUpdatedAt,
        )
    } else {
        candidate
    }
}

internal fun isMicStatusBroadcastNewer(
    rooms: List<OrgApi.Room>,
    update: RoomMicStatusBroadcast,
    currentRoomId: String?,
): Boolean {
    if (update.roomId == currentRoomId) return false
    val existing = rooms.firstOrNull { it.id == update.roomId }
    return existing == null || isNewerMicStatus(existing.statusUpdatedAt, update.statusUpdatedAt)
}

internal fun mergeMicStatusBroadcast(
    rooms: List<OrgApi.Room>,
    update: RoomMicStatusBroadcast,
    currentRoomId: String?,
): List<OrgApi.Room> {
    if (!isMicStatusBroadcastNewer(rooms, update, currentRoomId)) return rooms
    val updatedRoom = OrgApi.Room(
        id = update.roomId,
        microphoneId = update.microphoneId,
        displayName = update.displayName,
        status = update.status,
        statusUpdatedAt = update.statusUpdatedAt,
    )
    val existingIndex = rooms.indexOfFirst { it.id == update.roomId }
    return if (existingIndex >= 0) {
        rooms.toMutableList().apply { this[existingIndex] = updatedRoom }
    } else {
        rooms + updatedRoom
    }
}

@Composable
private fun MicStatusesDrawer(
    initialOrgId: String?,
    currentRoomId: String?,
    onDismiss: () -> Unit,
) {
    var rooms by remember { mutableStateOf<List<OrgApi.Room>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var commandError by remember { mutableStateOf<MicCommandError?>(null) }
    var resolvedOrgId by remember(initialOrgId) {
        mutableStateOf(initialOrgId?.takeIf { it.isNotBlank() })
    }
    var pendingCommands by remember {
        mutableStateOf<Map<String, PendingMicCommand>>(emptyMap())
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var panelVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val closeThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }

    fun closeDrawer() {
        if (!panelVisible) return
        panelVisible = false
        scope.launch {
            delay(MIC_DRAWER_ANIMATION_MS.toLong())
            onDismiss()
        }
    }

    fun dispatchCommand(room: OrgApi.Room, command: RoomMicCommand) {
        val orgId = resolvedOrgId
        if (orgId.isNullOrBlank() || pendingCommands.containsKey(room.id)) return
        val pending = PendingMicCommand(command, System.nanoTime())
        pendingCommands = pendingCommands + (room.id to pending)
        commandError = null
        scope.launch {
            try {
                OrgApi.sendRoomMicCommand(orgId, room.id, command.apiValue)
            } catch (failure: Throwable) {
                if (pendingCommands[room.id]?.token == pending.token) {
                    pendingCommands = pendingCommands - room.id
                    val disconnectedFailure = messageForDisconnectedApiFailure(
                        pending.command,
                        failure.message,
                        room.displayName,
                    )
                    commandError = MicCommandError(
                        roomId = room.id,
                        message = disconnectedFailure
                            ?: failure.message
                            ?: "Could not send microphone command.",
                        clearsWhenMicReconnects = disconnectedFailure != null,
                    )
                }
                return@launch
            }

            delay(MIC_COMMAND_CONFIRM_TIMEOUT_MS)
            if (pendingCommands[room.id]?.token == pending.token) {
                pendingCommands = pendingCommands - room.id
                commandError = MicCommandError(
                    roomId = room.id,
                    message = "${room.displayName} did not confirm the command.",
                )
            }
        }
    }

    LaunchedEffect(initialOrgId, currentRoomId) {
        val orgId = initialOrgId?.takeIf { it.isNotBlank() }
            ?: runCatching { OrgApi.getOrgs().firstOrNull()?.id }.getOrNull()
        if (orgId.isNullOrBlank()) {
            error = "No organization is assigned to this tablet."
            loading = false
            return@LaunchedEffect
        }
        resolvedOrgId = orgId

        suspend fun refreshSnapshot() {
            runCatching { OrgApi.getRooms(orgId) }
                .onSuccess { fetched ->
                    val visible = mergeMicStatusSnapshot(rooms, fetched, currentRoomId)
                    rooms = visible
                    val unresolved = pendingCommands.toMutableMap()
                    pendingCommands.forEach { (roomId, pending) ->
                        val room = visible.firstOrNull { it.id == roomId } ?: return@forEach
                        val failureMessage = micCommandFailureMessage(
                            pending.command,
                            room.status,
                            room.displayName,
                        )
                        if (failureMessage != null) {
                            unresolved.remove(roomId)
                            commandError = MicCommandError(
                                roomId = roomId,
                                message = failureMessage,
                                clearsWhenMicReconnects = true,
                            )
                        } else if (isMicCommandConfirmed(pending.command, room.status)) {
                            unresolved.remove(roomId)
                        }
                    }
                    pendingCommands = unresolved
                    commandError?.let { currentError ->
                        if (visible.any { shouldClearMicCommandError(currentError, it) }) {
                            commandError = null
                        }
                    }
                    error = null
                }
                .onFailure { failure ->
                    error = failure.message ?: "Could not load microphone statuses."
                }
            loading = false
        }

        // Render a snapshot immediately. Once Broadcast subscribes it refreshes
        // again, closing the small gap between this request and channel setup.
        refreshSnapshot()
        try {
            RoomMicStatusBroadcasts.listen(
                orgId = orgId,
                onConnected = { refreshSnapshot() },
                onStatus = statusUpdate@{ update ->
                    if (!isMicStatusBroadcastNewer(rooms, update, currentRoomId)) {
                        return@statusUpdate
                    }
                    rooms = mergeMicStatusBroadcast(rooms, update, currentRoomId)
                    commandError?.let { currentError ->
                        val updatedRoom = rooms.firstOrNull { it.id == update.roomId }
                        if (updatedRoom != null && shouldClearMicCommandError(currentError, updatedRoom)) {
                            commandError = null
                        }
                    }
                    pendingCommands[update.roomId]?.let { pending ->
                        val failureMessage = micCommandFailureMessage(
                            pending.command,
                            update.status,
                            update.displayName,
                        )
                        if (failureMessage != null) {
                            pendingCommands = pendingCommands - update.roomId
                            commandError = MicCommandError(
                                roomId = update.roomId,
                                message = failureMessage,
                                clearsWhenMicReconnects = true,
                            )
                        } else if (isMicCommandConfirmed(pending.command, update.status)) {
                            pendingCommands = pendingCommands - update.roomId
                        }
                    }
                    error = null
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: "Live microphone updates are unavailable."
            DiagnosticsLogger.warn(
                "mic_status_broadcast_failed",
                mapOf("orgId" to orgId),
                failure,
            )
        }
    }

    LaunchedEffect(Unit) {
        panelVisible = true
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Dialog(
        onDismissRequest = { closeDrawer() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = { closeDrawer() }),
            )
            AnimatedVisibility(
                visible = panelVisible,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(MIC_DRAWER_ANIMATION_MS),
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(MIC_DRAWER_ANIMATION_MS),
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(480.dp)
                        .pointerInput(onDismiss, closeThresholdPx) {
                            var dragDistance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onHorizontalDrag = { _, dragAmount -> dragDistance += dragAmount },
                                onDragEnd = {
                                    if (dragDistance >= closeThresholdPx) closeDrawer()
                                    dragDistance = 0f
                                },
                                onDragCancel = { dragDistance = 0f },
                            )
                        },
                    shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                    color = VuedSurfaceRaised,
                    shadowElevation = 20.dp,
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "Microphones",
                                color = VuedTextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.sp,
                            )
                            Text(
                                text = "Live status by room",
                                color = VuedTextTertiary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.sp,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                loading -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 28.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                            color = VuedTextTertiary,
                                        )
                                    }
                                }
                                rooms.isEmpty() -> {
                                    Text(
                                        text = error ?: "No other microphones have reported a status yet.",
                                        color = if (error == null) VuedTextTertiary else VuedDanger,
                                        fontSize = 14.sp,
                                    )
                                }
                                else -> {
                                    rooms.forEach { room ->
                                        val online = isMicOnline(room, nowMs)
                                        val unavailable = shouldShowMicUnavailableIndicator(room, nowMs)
                                        val recording = room.status in
                                            setOf("ambient_recording", "meeting_recording")
                                        val muted = room.status in setOf("ambient_muted", "meeting_muted")
                                        val muteEnabled = online &&
                                            (muted || room.status == "ambient_recording")
                                        val pending = pendingCommands[room.id]
                                        val muteCommand = if (muted) {
                                            RoomMicCommand.UNMUTE
                                        } else {
                                            RoomMicCommand.MUTE
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(VuedSurface)
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = room.displayName,
                                                color = VuedTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (online && room.status == "meeting_recording") {
                                                    MeetingRecordingDot()
                                                }
                                                if (unavailable) {
                                                    MicDisconnectedIndicator(
                                                        contentDescription = if (online) {
                                                            "Microphone disconnected"
                                                        } else {
                                                            "Tablet offline"
                                                        },
                                                    )
                                                } else {
                                                    AudioMuteButton(
                                                        unmuted = recording,
                                                        enabled = muteEnabled && pending == null,
                                                        buttonSize = 46.dp,
                                                        iconSize = 25.dp,
                                                        onClick = { dispatchCommand(room, muteCommand) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (error != null) {
                                        Text(
                                            text = "Live updates: $error",
                                            color = VuedDanger,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    if (commandError != null) {
                                        Text(
                                            text = commandError?.message.orEmpty(),
                                            color = VuedDanger,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { closeDrawer() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VuedTextPrimary,
                                    contentColor = Color.White,
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = "Done",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugExceptionButton() {
    OutlinedButton(
        onClick = {
            throw IllegalStateException("Debug Sentry test exception from Vued host")
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFFF2B8B5)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFFFFFBFA),
            contentColor = Color(0xFFB42318),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Crash",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun MeetingCircleButton(
    meetingActive: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor = when {
        !enabled -> VuedIdleRing
        meetingActive -> VuedTextPrimary
        else -> VuedSuccess
    }
    val textColor = when {
        !enabled -> VuedTextTertiary
        meetingActive -> Color.White
        else -> VuedSuccess
    }
    val fillColor = if (meetingActive) VuedTextPrimary else Color.Transparent

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(fillColor, CircleShape)
            .border(BorderStroke(10.dp, borderColor), CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (meetingActive) "End\nMeeting" else "Start\nMeeting",
            color = textColor,
            fontSize = 68.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 72.sp,
            letterSpacing = 0.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AudioMuteButton(
    unmuted: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 112.dp,
    iconSize: Dp = 60.dp,
    onClick: () -> Unit,
) {
    val iconColor = Color.White
    val containerColor = if (unmuted) VuedSuccess else VuedDanger
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = modifier
            .size(buttonSize)
            .semantics { contentDescription = if (unmuted) "Mute" else "Unmute" },
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = iconColor,
            disabledContainerColor = containerColor,
            disabledContentColor = iconColor,
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier.size(iconSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * (4f / 60f)
                val micCenterX = size.width * 0.5f
                val micTop = size.height * 0.13f
                val micSize = Size(size.width * 0.34f, size.height * 0.48f)

                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(micCenterX - micSize.width / 2f, micTop),
                    size = micSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        micSize.width / 2f,
                        micSize.width / 2f,
                    ),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = iconColor,
                    startAngle = 18f,
                    sweepAngle = 144f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.24f, size.height * 0.35f),
                    size = Size(size.width * 0.52f, size.height * 0.34f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(micCenterX, size.height * 0.69f),
                    end = Offset(micCenterX, size.height * 0.84f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width * 0.36f, size.height * 0.84f),
                    end = Offset(size.width * 0.64f, size.height * 0.84f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )

                if (!unmuted) {
                    drawLine(
                        color = iconColor,
                        start = Offset(size.width * 0.22f, size.height * 0.18f),
                        end = Offset(size.width * 0.82f, size.height * 0.82f),
                        strokeWidth = stroke * 1.15f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MicDisconnectedIndicator(
    modifier: Modifier = Modifier,
    contentDescription: String = "Microphone disconnected",
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val stroke = size.minDimension * 0.09f
            drawCircle(
                color = VuedDanger,
                style = Stroke(width = stroke),
            )
            drawLine(
                color = VuedDanger,
                start = Offset(size.width * 0.5f, size.height * 0.24f),
                end = Offset(size.width * 0.5f, size.height * 0.58f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = VuedDanger,
                radius = stroke * 0.62f,
                center = Offset(size.width * 0.5f, size.height * 0.74f),
            )
        }
    }
}

@Composable
private fun AddSpeakerButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.size(46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = VuedTextTertiary,
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .border(1.dp, VuedHairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 1.7.dp.toPx()
                drawCircle(
                    color = VuedTextTertiary,
                    radius = 4.2.dp.toPx(),
                    center = Offset(size.width * 0.38f, size.height * 0.35f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = VuedTextTertiary,
                    startAngle = 202f,
                    sweepAngle = 136f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.14f, size.height * 0.52f),
                    size = Size(size.width * 0.48f, size.height * 0.34f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(
                    color = VuedTextTertiary,
                    start = Offset(size.width * 0.72f, size.height * 0.36f),
                    end = Offset(size.width * 0.72f, size.height * 0.76f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = VuedTextTertiary,
                    start = Offset(size.width * 0.52f, size.height * 0.56f),
                    end = Offset(size.width * 0.92f, size.height * 0.56f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun MicConnectionBadge(status: MicConnectionUi) {
    Surface(
        modifier = Modifier.semantics { contentDescription = status.contentDescription },
        shape = RoundedCornerShape(8.dp),
        color = VuedSurfaceRaised.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, VuedHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic_24),
                contentDescription = null,
                tint = status.color,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = status.label,
                color = VuedTextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            )
        }
    }
}

@Composable
private fun SelfUpdateButton(
    versionCode: Int,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, VuedHairline),
        modifier = modifier.semantics {
            contentDescription = "Check for app updates. Current version: $versionCode"
        },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = VuedSurfaceRaised.copy(alpha = 0.92f),
            contentColor = VuedTextTertiary,
            disabledContainerColor = VuedSurfaceRaised.copy(alpha = 0.72f),
            disabledContentColor = VuedTextTertiary.copy(alpha = 0.45f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = VuedTextTertiary,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_update_24),
                    contentDescription = null,
                    tint = VuedTextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (busy) "Checking updates" else "Check for updates",
                    color = VuedTextTertiary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                )
                Text(
                    text = "Current version: $versionCode",
                    color = VuedTextTertiary.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                )
            }
        }
    }
}

@Composable
private fun SelfUpdateDialog(
    title: String,
    busy: Boolean,
    message: String,
    versionCode: Int,
    onDismiss: () -> Unit,
) {
    val statusColor = when {
        title.contains("unavailable", ignoreCase = true) -> Color(0xFFB42318)
        title.contains("up to date", ignoreCase = true) -> VuedSuccess
        title.contains("install", ignoreCase = true) -> VuedSuccess
        else -> VuedTextTertiary
    }

    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp),
            shape = RoundedCornerShape(18.dp),
            color = VuedSurfaceRaised,
            shadowElevation = 20.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = statusColor,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_update_24),
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = title,
                            color = VuedTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        )
                        Text(
                            text = if (busy) "Update check in progress" else "Software update",
                            color = VuedTextTertiary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                        )
                    }
                }

                Text(
                    text = message,
                    color = VuedTextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    letterSpacing = 0.sp,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(VuedSurface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Current version",
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                        color = VuedTextTertiary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.sp,
                    )
                    Text(
                        text = versionCode.toString(),
                        modifier = Modifier.padding(end = 12.dp, top = 10.dp, bottom = 10.dp),
                        color = VuedTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        Text(
                            text = "Keep Vued open",
                            color = VuedTextTertiary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                        )
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VuedTextPrimary,
                                contentColor = Color.White,
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = "Done",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun updateTitleForProgress(message: String): String = when {
    message.startsWith("Downloading") -> "Downloading update"
    message.startsWith("Preparing") -> "Installing update"
    else -> "Checking for updates"
}

@Composable
private fun WifiSettingsButton(
    status: WifiStatus,
    labelOverride: String? = null,
    onClick: () -> Unit,
) {
    val color = if (status.connected) VuedSuccess else Color(0xFFB42318)
    val statusLabel = when {
        status.ssid != null -> status.ssid
        status.connected -> "Wi-Fi"
        else -> "No Wi-Fi"
    }
    val label = labelOverride ?: statusLabel
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, VuedHairline),
        modifier = Modifier
            .semantics {
                contentDescription = if (status.connected) {
                    "Wi-Fi connected: $statusLabel"
                } else {
                    "$label, Wi-Fi disconnected"
                }
            },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = VuedSurfaceRaised.copy(alpha = 0.92f),
            contentColor = color,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_wifi_24),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = VuedTextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            )
        }
    }
}

private fun isUmaMicPresent(context: Context): Boolean =
    Uma8Capture(context).findDevice() != null

private fun micConnectionUi(
    micArrayPresent: Boolean,
    status: RecorderState.Status,
): MicConnectionUi =
    when {
        micArrayPresent && !status.micDisconnected -> MicConnectionUi(
            label = "Connected",
            color = VuedSuccess,
            contentDescription = "UMA microphone connected",
        )
        status.hasFreshAudio() -> MicConnectionUi(
            label = "Tablet mic",
            color = VuedSuccess,
            contentDescription = "Recording with tablet microphone",
        )
        else -> MicConnectionUi(
            label = "Disconnected",
            color = Color(0xFFB42318),
            contentDescription = "Mic disconnected",
        )
    }

private fun formatSegmentTime(totalSecs: Long): String {
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatCurrentTime(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

@Composable
private fun BatteryStatusBadge(
    status: BatteryStatus,
    modifier: Modifier = Modifier,
) {
    val level = status.levelPercent?.coerceIn(0, 100)
    val color = when {
        level != null && level <= 15 && !status.charging -> Color(0xFFB42318)
        status.charging -> VuedSuccess
        else -> VuedTextTertiary
    }
    val label = buildString {
        append(level?.let { "$it%" } ?: "--%")

    }

    Surface(
        modifier = modifier.semantics { contentDescription = batteryContentDescription(status) },
        shape = RoundedCornerShape(8.dp),
        color = VuedSurfaceRaised.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, VuedHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(width = 28.dp, height = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                BatteryIcon(
                    levelPercent = level,
                    charging = status.charging,
                    color = color,
                    modifier = Modifier.size(width = 24.dp, height = 14.dp),
                )
                if (status.charging) {
                    ChargingBolt(modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                text = label,
                color = VuedTextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            )
        }
    }
}

@Composable
private fun BatteryIcon(
    levelPercent: Int?,
    charging: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val capWidth = 2.8.dp.toPx()
        val bodyWidth = size.width - capWidth - stroke
        val corner = 2.dp.toPx()
        val levelFraction = (levelPercent ?: 0).coerceIn(0, 100) / 100f

        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyWidth, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth + stroke, size.height * 0.32f),
            size = Size(capWidth, size.height * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke, stroke),
        )
        if (levelFraction > 0f) {
            val fillInset = stroke * 2.1f
            drawRoundRect(
                color = color.copy(alpha = if (charging) 0.92f else 0.68f),
                topLeft = Offset(fillInset, fillInset),
                size = Size(
                    width = (bodyWidth - fillInset * 2f) * levelFraction,
                    height = size.height - fillInset * 2f,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * 0.7f, corner * 0.7f),
            )
        }
    }
}

@Composable
private fun ChargingBolt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val bolt = Path().apply {
            moveTo(size.width * 0.60f, size.height * 0.03f)
            lineTo(size.width * 0.27f, size.height * 0.58f)
            lineTo(size.width * 0.48f, size.height * 0.58f)
            lineTo(size.width * 0.35f, size.height * 0.97f)
            lineTo(size.width * 0.78f, size.height * 0.40f)
            lineTo(size.width * 0.56f, size.height * 0.40f)
            close()
        }
        drawPath(bolt, color = VuedChargingBolt)
    }
}

@Composable
private fun DevRecorderScreen(userEmail: String?, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val status by RecorderState.state.collectAsState()
    val scope = rememberCoroutineScope()

    var hasAudio by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var usbState by remember { mutableStateOf(describeUsb(context)) }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudio = granted }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                usbState = describeUsb(ctx)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Vued Recorder",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
        userEmail?.let { StatusLine("Signed in", it) }

        var roomName by remember { mutableStateOf(RoomConfig.roomName(context)) }
        var showRoomPicker by remember { mutableStateOf(false) }
        StatusLine("Room", roomName ?: "not set")
        OutlinedButton(onClick = { showRoomPicker = true }) { Text("Set room") }
        if (showRoomPicker) {
            RoomPickerDialog(
                onDismiss = { showRoomPicker = false },
                onPicked = { roomName = it },
            )
        }

        var ambientUnlocked by remember { mutableStateOf(AmbientDecryptor.isUnlocked(context)) }
        var ambientPassphrase by remember { mutableStateOf("") }
        var ambientMsg by remember { mutableStateOf(if (ambientUnlocked) "unlocked" else "locked") }
        StatusLine("Ambient decrypt", ambientMsg)
        if (!ambientUnlocked) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PassphraseTextField(
                    value = ambientPassphrase,
                    onValueChange = { ambientPassphrase = it },
                    label = "Passphrase",
                )
                Button(onClick = {
                    scope.launch {
                        ambientMsg = "unlocking…"
                        ambientMsg = try {
                            AmbientDecryptor.unlock(context, ambientPassphrase)
                            ambientPassphrase = ""
                            ambientUnlocked = true
                            "unlocked"
                        } catch (e: Throwable) {
                            "unlock failed: ${e.message}"
                        }
                    }
                }) { Text("Unlock ambient") }
            }
        }

        var showEnroll by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showEnroll = true }) { Text("Enroll speaker") }
        if (showEnroll) {
            SpeakerEnrollmentDialog(onDismiss = { showEnroll = false })
        }

        StatusLine("Mic array", usbState)
        var micSelection by remember { mutableStateOf(MicArrayConfig.selection(context)) }
        StatusLine("Array config", micSelection.uiLabel())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicArraySelection.values().forEach { option ->
                if (option == micSelection) {
                    Button(onClick = {}) { Text(option.uiLabel()) }
                } else {
                    OutlinedButton(onClick = {
                        MicArrayConfig.set(context, option)
                        micSelection = option
                    }) { Text(option.uiLabel()) }
                }
            }
        }
        StatusLine("Mic permission", if (hasAudio) "granted" else "NOT granted")
        StatusLine("Service", if (status.running) "RECORDING" else "stopped")
        StatusLine("Capture ready", if (status.hasFreshAudio()) "yes" else "no")
        StatusLine("Segments written", status.segmentCount.toString())
        status.lastSegment?.let { StatusLine("Last segment", it) }
        status.error?.let { StatusLine("Error", it) }

        PeakMeter(status.peakDb)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!hasAudio) {
                    audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                requestUma8Permission(context)
                RecordingService.start(context)
            }) {
                Text("Start Recording")
            }
            OutlinedButton(onClick = { RecordingService.stop(context) }) {
                Text("Stop")
            }
        }

        var meetingActive by remember { mutableStateOf(MeetingController.active != null) }
        var meetingMsg by remember { mutableStateOf<String?>(null) }
        val devCaptureReady = status.running && status.hasFreshAudio()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = meetingActive || devCaptureReady,
                onClick = {
                    scope.launch {
                        try {
                            if (meetingActive) {
                                meetingMsg = "exporting + uploading…"
                                val result = MeetingController.stop(context)
                                meetingActive = false
                                meetingMsg = "Uploaded ${result.meetingId.take(8)}… " +
                                    "(${"%.1f".format(result.durationSecs)}s) — transcribing"
                            } else {
                                val id = MeetingController.start(context, "Meeting")
                                meetingActive = true
                                meetingMsg = "Recording meeting ${id.take(8)}…"
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "dev meeting action failed: ${e.message}", e)
                            meetingActive = MeetingController.active != null
                            meetingMsg = "meeting error: ${e.message}"
                        }
                    }
                },
            ) {
                Text(if (meetingActive) "Stop Meeting" else "Start Meeting!")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    meetingMsg = "flushing ambient…"
                    meetingMsg = try {
                        "ambient: " + AmbientFlusher.flushOnce(context) +
                            "; " + AmbientProcessor.processOnce(context)
                    } catch (e: Throwable) {
                        Log.w(TAG, "dev ambient flush failed: ${e.message}", e)
                        "ambient flush error: ${e.message}"
                    }
                }
            }) {
                Text("Flush Ambient")
            }
        }
        meetingMsg?.let { StatusLine("Meeting", it) }

        OutlinedButton(onClick = {
            requestUma8Permission(context)
            usbState = describeUsb(context)
        }) {
            Text("Grant UMA-8 USB access")
        }

        val deviceOwner = remember { isDeviceOwner(context) }
        var kioskMessage by remember { mutableStateOf<String?>(null) }
        StatusLine("Device owner", if (deviceOwner) "YES" else "no (run adb dpm cmd)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                (context as? Activity)?.let { kioskMessage = startKiosk(it) }
            }) {
                Text("Lock Down")
            }
            OutlinedButton(onClick = {
                (context as? Activity)?.let { kioskMessage = stopKiosk(it) }
            }) {
                Text("Unlock")
            }
            OutlinedButton(onClick = {
                openWifiSettings(context)
                kioskMessage = "Opened Wi-Fi settings"
            }) {
                Text("Wi-Fi Settings")
            }
        }
        kioskMessage?.let { StatusLine("Kiosk", it) }

    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF666666),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PeakMeter(peakDb: Float) {
    val fraction = if (peakDb.isFinite()) ((peakDb + 60f) / 60f).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Level: ${if (peakDb.isFinite()) "%.1f dBFS".format(peakDb) else "-inf"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .background(Color(0xFF2B3138))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(16.dp)
                    .background(
                        when {
                            peakDb >= -6f -> Color(0xFFE24B3B)
                            peakDb >= -18f -> Color(0xFFE0A72E)
                            else -> Color(0xFF29A36A)
                        }
                    )
            )
        }
    }
}

private fun describeUsb(context: Context): String {
    val capture = Uma8Capture(context)
    val device = capture.findDevice() ?: return "not connected"
    return if (capture.hasPermission(device)) "connected (authorized)" else "connected (needs permission)"
}

private fun MicArraySelection.uiLabel(): String = when (this) {
    MicArraySelection.AUTO -> "Auto"
    MicArraySelection.UMA8 -> "UMA-8"
    MicArraySelection.UMA16 -> "UMA-16"
}

private fun requestUma8Permission(context: Context) {
    if (context is MainActivity && context.requestUmaPermissionForKioskRecovery()) return

    val usbManager = context.getSystemService(UsbManager::class.java)
    val device = Uma8Capture(context).findDevice() ?: return
    if (usbManager.hasPermission(device)) return
    if (context is Activity && isKioskLocked(context)) {
        stopKiosk(context)
    }
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_MUTABLE,
    )
    usbManager.requestPermission(device, intent)
}

private fun requestUma8PermissionForStart(context: Context): Boolean {
    if (context is MainActivity) {
        return context.requestUmaPermissionForRecorderStart()
    }

    val usbManager = context.getSystemService(UsbManager::class.java)
    val device = Uma8Capture(context).findDevice() ?: return false
    if (usbManager.hasPermission(device)) return false
    requestUma8Permission(context)
    return true
}

private fun openWifiSettings(context: Context) {
    (context as? Activity)?.let { activity ->
        if (isKioskLocked(activity)) stopKiosk(activity)
    }
    val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
    val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
    runCatching {
        context.startActivity(wifiIntent)
    }.recoverCatching {
        context.startActivity(fallbackIntent)
    }.onFailure { error ->
        Log.w(TAG, "Failed to open Wi-Fi settings: ${error.message}", error)
    }
}

private fun currentWifiStatus(context: Context): WifiStatus {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork ?: return WifiStatus(false, null)
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return WifiStatus(false, null)
    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return WifiStatus(false, null)
    }

    val ssidFromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (capabilities.transportInfo as? WifiInfo)?.ssid.cleanSsid()
    } else {
        null
    }
    val ssidFromManager = runCatching {
        context.getSystemService(WifiManager::class.java).connectionInfo?.ssid.cleanSsid()
    }.getOrNull()

    return WifiStatus(true, ssidFromCapabilities ?: ssidFromManager)
}

private fun String?.cleanSsid(): String? {
    val value = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        ?: return null
    return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        value.substring(1, value.lastIndex)
    } else {
        value
    }
}

private fun currentBatteryStatus(context: Context): BatteryStatus {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return BatteryStatus(
            levelPercent = null,
            charging = false,
            plugged = false,
            powerSource = null,
        )
    return batteryStatusFromIntent(intent)
}

private fun batteryStatusFromIntent(intent: Intent): BatteryStatus {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).toInt().coerceIn(0, 100)
    } else {
        null
    }
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val pluggedValue = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

    return BatteryStatus(
        levelPercent = percent,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL,
        plugged = pluggedValue != 0,
        powerSource = pluggedValue.powerSourceLabel(),
    )
}

private fun Int.powerSourceLabel(): String? = when {
    this and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
    this and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
    this and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        this and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "dock"
    else -> null
}

private fun batteryContentDescription(status: BatteryStatus): String = buildString {
    append("Battery ")
    append(status.levelPercent?.let { "$it percent" } ?: "level unknown")
}
