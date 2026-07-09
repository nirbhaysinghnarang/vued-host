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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.nsn8.vued.USB_PERMISSION"
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

private val VuedBackground = Color(0xFFFFFFFF)
private val VuedSurface = Color(0xFFF8F9FB)
private val VuedSurfaceRaised = Color(0xFFFFFFFF)
private val VuedHairline = Color(0xFFD6DDE6)
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
        enableEdgeToEdge()
        setContent {
            VuedTheme(desktopTheme = HOST_UI_MODE == HostUiMode.PROD) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDeviceOwner(this)) {
            Log.i(TAG, startKiosk(this))
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
            .statusBarsPadding()
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
                        "Unlock this tablet to process ambient candidates locally."
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
                        label = "Enter your passphrase to be able to decrypt your meetings",
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
            .statusBarsPadding()
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
    var wifiStatus by remember { mutableStateOf(currentWifiStatus(context)) }
    var batteryStatus by remember { mutableStateOf(currentBatteryStatus(context)) }
    var currentTime by remember { mutableStateOf(formatCurrentTime()) }

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

    LaunchedEffect(captureReady, status.running, status.micDisconnected) {
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

    val elapsedSecs = if (meetingActive) {
        ((nowMs - segmentStartedAt) / 1000).coerceAtLeast(0)
    } else {
        0
    }
    val showMicDisconnected = !VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK && status.micDisconnected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VuedBackground)
            .statusBarsPadding()
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

        SourceWavIndicator(
            status = status,
            meetingActive = meetingActive,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 4.dp)
                .widthIn(max = 280.dp),
        )

        AudioMuteButton(
            unmuted = captureReady,
            enabled = !status.running || status.captureReady,
            modifier = Modifier.align(Alignment.TopStart),
            onClick = {
                if (status.running) RecordingService.stop(context) else startCapture()
            },
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (VuedConfig.ALLOW_BUILT_IN_MIC_FALLBACK) {
                WifiSettingsButton(
                    status = wifiStatus,
                    onClick = {
                        openWifiSettings(context)
                        wifiStatus = currentWifiStatus(context)
                    },
                )
            }
            BatteryStatusBadge(status = batteryStatus)
            AddSpeakerButton(onClick = { showEnroll = true })
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
    }

    if (showEnroll) {
        ProdSpeakerEnrollmentDialog(onDismiss = { showEnroll = false })
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
private fun SourceWavIndicator(
    status: RecorderState.Status,
    meetingActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = status.sourceWavRecording
    val tone = if (active) VuedSuccess else VuedTextTertiary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (active) VuedSuccessSoft else VuedSurface,
        border = BorderStroke(1.dp, if (active) VuedSuccess.copy(alpha = 0.38f) else VuedHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "16ch WAV",
                    color = VuedTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                )
                Text(
                    text = sourceWavStatusText(status, meetingActive),
                    color = VuedTextTertiary,
                    fontSize = 12.sp,
                    letterSpacing = 0.sp,
                )
            }
        }
    }
}

private fun sourceWavStatusText(status: RecorderState.Status, meetingActive: Boolean): String {
    if (!status.running) {
        return if (status.sourceWavSegmentCount > 0) {
            "stopped · ${status.sourceWavSegmentCount} chunks saved"
        } else {
            "stopped"
        }
    }
    if (!status.sourceWavRecording && status.sourceWavSegmentCount == 0) {
        return "waiting for UMA-16"
    }
    val scope = if (meetingActive) "meeting + ambient" else "ambient chunks"
    val verb = if (status.sourceWavRecording) "saving" else "paused"
    return "$verb $scope · ${status.sourceWavSegmentCount} chunks"
}

@Composable
private fun AudioMuteButton(
    unmuted: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconColor = if (unmuted && enabled) Color.White else VuedTextTertiary
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = modifier
            .size(112.dp)
            .semantics { contentDescription = if (unmuted) "Mute" else "Unmute" },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (unmuted) VuedSuccess else VuedIdleRing,
            contentColor = iconColor,
            disabledContainerColor = VuedIdleRing,
            disabledContentColor = VuedTextTertiary,
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
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
private fun WifiSettingsButton(
    status: WifiStatus,
    onClick: () -> Unit,
) {
    val color = if (status.connected) VuedSuccess else Color(0xFFB42318)
    val label = when {
        status.ssid != null -> status.ssid
        status.connected -> "Wi-Fi"
        else -> "No Wi-Fi"
    }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, VuedHairline),
        modifier = Modifier
            .semantics {
                contentDescription = if (status.connected) {
                    "Wi-Fi connected: $label"
                } else {
                    "Wi-Fi disconnected"
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
        when {
            status.charging -> append(" Charging")
            status.plugged -> append(" Plugged")
        }
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
            BatteryIcon(
                levelPercent = level,
                charging = status.charging,
                color = color,
                modifier = Modifier.size(width = 24.dp, height = 14.dp),
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
        var meetingActive by remember { mutableStateOf(MeetingController.active != null) }
        var meetingMsg by remember { mutableStateOf<String?>(null) }

        StatusLine("Mic permission", if (hasAudio) "granted" else "NOT granted")
        StatusLine("Service", if (status.running) "RECORDING" else "stopped")
        StatusLine("Capture ready", if (status.hasFreshAudio()) "yes" else "no")
        StatusLine("Segments written", status.segmentCount.toString())
        status.lastSegment?.let { StatusLine("Last segment", it) }
        StatusLine("16ch WAV", sourceWavStatusText(status, meetingActive))
        StatusLine("16ch chunks", status.sourceWavSegmentCount.toString())
        status.lastSourceWavSegment?.let { StatusLine("Last 16ch chunk", it) }
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
                                    "(${"%.1f".format(result.durationSecs)}s) — transcribing" +
                                    if (result.sourceWavPath != null) "; 16ch WAV queued" else ""
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
    when {
        status.charging && status.powerSource != null -> append(", charging via ${status.powerSource}")
        status.charging -> append(", charging")
        status.plugged && status.powerSource != null -> append(", plugged in via ${status.powerSource}")
        status.plugged -> append(", plugged in")
        else -> append(", not charging")
    }
}
