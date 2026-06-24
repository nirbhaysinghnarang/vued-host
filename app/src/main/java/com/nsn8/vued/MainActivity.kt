package com.nsn8.vued

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nsn8.vued.ambient.AmbientProcessor
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.capture.Uma8Capture
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.crypto.AmbientDecryptor
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.ui.RoomPickerDialog
import com.nsn8.vued.ui.SpeakerEnrollmentDialog
import com.nsn8.vued.service.RecorderState
import com.nsn8.vued.service.RecordingService
import com.nsn8.vued.ui.LoginScreen
import com.nsn8.vued.ui.theme.VuedTheme
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.nsn8.vued.USB_PERMISSION"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on during in-room operation so capture/upload remains
        // responsive while the recorder is foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        VuedAuth.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            VuedTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate()
                }
            }
        }
    }
}

@Composable
private fun AuthGate() {
    val status by VuedAuth.sessionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    when (status) {
        is SessionStatus.Authenticated -> {
            RecorderScreen(
                userEmail = VuedAuth.currentEmail(),
                onSignOut = { scope.launch { VuedAuth.signOut() } },
            )
        }
        is SessionStatus.RefreshFailure -> LoginScreen(initialError = "Session expired, sign in again")
        SessionStatus.Initializing -> LoadingScreen()
        is SessionStatus.NotAuthenticated -> LoginScreen()
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
private fun RecorderScreen(userEmail: String?, onSignOut: () -> Unit) {
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
            .statusBarsPadding()
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
                OutlinedTextField(
                    value = ambientPassphrase,
                    onValueChange = { ambientPassphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
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

        StatusLine("UMA-8", usbState)
        StatusLine("Mic permission", if (hasAudio) "granted" else "NOT granted")
        StatusLine("Service", if (status.running) "RECORDING" else "stopped")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
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
                        meetingActive = MeetingController.active != null
                        meetingMsg = "meeting error: ${e.message}"
                    }
                }
            }) {
                Text(if (meetingActive) "Stop Meeting" else "Start Meeting")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    meetingMsg = "flushing ambient…"
                    meetingMsg = try {
                        "ambient: " + AmbientFlusher.flushOnce(context) +
                            "; " + AmbientProcessor.processOnce(context)
                    } catch (e: Throwable) {
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

private fun requestUma8Permission(context: Context) {
    val usbManager = context.getSystemService(UsbManager::class.java)
    val device = Uma8Capture(context).findDevice() ?: return
    if (usbManager.hasPermission(device)) return
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_MUTABLE,
    )
    usbManager.requestPermission(device, intent)
}
