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
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.nsn8.vued.ui.LoginScreen
import com.nsn8.vued.ui.theme.VuedTheme
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.nsn8.vued.USB_PERMISSION"
private const val TAG = "VuedMainActivity"
private val HOST_UI_MODE = HostUiMode.PROD

private enum class HostUiMode { DEV, PROD }

private val VuedBackground = Color(0xFFFFFFFF)
private val VuedSurface = Color(0xFFF8F9FB)
private val VuedSurfaceRaised = Color(0xFFFFFFFF)
private val VuedHairline = Color(0xFFD6DDE6)
private val VuedHairlineStrong = Color(0xFFAEB9C8)
private val VuedTextPrimary = Color(0xFF0B0D12)
private val VuedTextSecondary = Color(0xFF2F3744)
private val VuedTextTertiary = Color(0xFF5B6573)
private val VuedSuccess = Color(0xFF16764F)
private val VuedSuccessSoft = Color(0xFFEAF8F1)
private val VuedIdleRing = Color(0xFFE5EAF0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on during in-room operation so capture/upload remains
        // responsive while the recorder is foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        VuedAuth.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            VuedTheme(desktopTheme = HOST_UI_MODE == HostUiMode.PROD) {
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
            .padding(32.dp),
        contentAlignment = Alignment.Center,
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
                        "Unlock this tablet so it can process ambient candidates locally."
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
                        label = "Passphrase",
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

@Composable
private fun RoomOnboardingScreen(onRoomPicked: (String) -> Unit) {
    val context = LocalContext.current
    var rooms by remember { mutableStateOf<List<OrgApi.Room>>(emptyList()) }
    var orgId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

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
            .padding(32.dp),
        contentAlignment = Alignment.Center,
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
                    rooms.isEmpty() -> Text("No rooms found. Create one in Vued first.", color = VuedTextSecondary)
                    else -> rooms.forEach { room ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = VuedTextPrimary,
                            ),
                            border = BorderStroke(1.dp, VuedHairline),
                            onClick = {
                                RoomConfig.set(
                                    context,
                                    room.id,
                                    room.displayName,
                                    orgId.orEmpty(),
                                    room.microphoneId,
                                )
                                onRoomPicked(room.displayName)
                            },
                        ) {
                            Text(
                                text = room.displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                color = VuedTextPrimary,
                                fontSize = 17.sp,
                                letterSpacing = 0.sp,
                            )
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

    var hasAudio by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudio = granted }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var meetingActive by remember { mutableStateOf(MeetingController.active != null) }
    var segmentStartedAt by remember { mutableStateOf(MeetingController.active?.startMs ?: 0L) }
    var segmentBusy by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showEnroll by remember { mutableStateOf(false) }

    LaunchedEffect(meetingActive, segmentStartedAt) {
        while (meetingActive) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }

    fun startCapture() {
        if (!hasAudio) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestUma8Permission(context)
        RecordingService.start(context)
    }

    val ringColor = if (status.running) VuedSuccess else VuedIdleRing
    val ringSoftColor = if (status.running) VuedSuccessSoft else VuedSurface
    val elapsedSecs = if (meetingActive) {
        ((nowMs - segmentStartedAt) / 1000).coerceAtLeast(0)
    } else {
        0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VuedBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 0.dp),
    ) {
        AddSpeakerButton(
            modifier = Modifier.align(Alignment.TopEnd),
            onClick = { showEnroll = true },
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val ringSize = minOf(maxWidth * 0.96f, maxHeight - 250.dp)
            Column(
                modifier = Modifier.offset(y = (-92).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    enabled = !segmentBusy && (status.running || meetingActive),
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
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (meetingActive) VuedTextPrimary else VuedSuccess,
                        contentColor = Color.White,
                        disabledContainerColor = VuedSurface,
                        disabledContentColor = VuedTextTertiary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .height(128.dp)
                        .width(470.dp),
                ) {
                    Text(
                        text = if (meetingActive) "End Meeting" else "Start Meeting",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    )
                }

                Text(
                    text = if (meetingActive) formatSegmentTime(elapsedSecs) else "     ",
                    modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                    color = VuedTextTertiary.copy(alpha = 0.72f),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Thin,
                    letterSpacing = 0.sp,
                )

                Box(
                    modifier = Modifier.size(ringSize),
                    contentAlignment = Alignment.Center,
                ) {
                    RecordingRing(
                        ringColor = ringColor,
                        softColor = ringSoftColor,
                        running = status.running,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Button(
                        onClick = {
                            if (status.running) RecordingService.stop(context) else startCapture()
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(188.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = VuedTextPrimary,
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                        ),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (status.running) "Mute" else "Unmute",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        )
                    }
                }
            }
        }
    }

    if (showEnroll) {
        ProdSpeakerEnrollmentDialog(onDismiss = { showEnroll = false })
    }
}

@Composable
private fun RecordingRing(
    ringColor: Color,
    softColor: Color,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val stroke = diameter * 0.046f
        val ringRadius = diameter * 0.43f

        drawCircle(
            color = if (running) softColor.copy(alpha = 0.96f) else softColor.copy(alpha = 0.82f),
            radius = ringRadius,
            center = center,
        )
        drawCircle(
            color = ringColor,
            radius = ringRadius,
            center = center,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
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

private fun formatSegmentTime(totalSecs: Long): String {
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return "%02d:%02d".format(minutes, seconds)
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
                        Log.w(TAG, "dev meeting action failed: ${e.message}", e)
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
