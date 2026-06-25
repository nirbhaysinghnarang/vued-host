package com.nsn8.vued.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nsn8.vued.audio.EnrollmentQualityResult
import com.nsn8.vued.audio.EnrollmentRecorder
import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.net.VuedApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class EnrollMode { MEMBER, TEMP }

private val ProdBackground = Color(0xFFFFFFFF)
private val ProdSurface = Color(0xFFF8F9FB)
private val ProdHairline = Color(0xFFD6DDE6)
private val ProdTextPrimary = Color(0xFF0B0D12)
private val ProdTextSecondary = Color(0xFF2F3744)
private val ProdTextTertiary = Color(0xFF5B6573)
private val ProdAccent = Color(0xFFEF6F2E)
private val ProdSuccess = Color(0xFF16764F)

@Composable
fun ProdSpeakerEnrollmentDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recorder = remember { EnrollmentRecorder() }

    var mode by remember { mutableStateOf(EnrollMode.MEMBER) }
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<OrgApi.Member>>(emptyList()) }
    var orgId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<OrgApi.Member?>(null) }

    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var level by remember { mutableStateOf(0f) }
    var captured by remember { mutableStateOf<EnrollmentRecorder.Result?>(null) }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<VuedApi.SpeakerProfile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val org = OrgApi.getOrgs().firstOrNull()
            orgId = org?.id
            if (org != null) members = OrgApi.getMembers(org.id)
        } catch (_: Exception) {
            // Temp enrollment still works if member lookup fails.
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.secondsCaptured
            level = recorder.liveLevel
            delay(60)
        }
        level = 0f
    }

    val displayName = if (mode == EnrollMode.MEMBER) selected?.displayName.orEmpty() else name.trim()

    fun enroll(profileId: String?) {
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                val sample = captured ?: error("Record a sample first.")
                val profile = VuedApi.enrollSpeaker(
                    displayName,
                    sample.wav,
                    profileId = profileId,
                    durationSecs = sample.quality.sourceSecs,
                    isOrgUser = mode == EnrollMode.MEMBER,
                )
                val member = selected
                if (mode == EnrollMode.MEMBER && member != null && orgId != null) {
                    OrgApi.linkSpeakerProfile(orgId!!, profile.id, member.userId)
                }
                status = "Saved ${profile.displayName}"
                duplicates = emptyList()
            } catch (e: VuedApi.DuplicateSpeakerException) {
                duplicates = e.profiles
                error = "This speaker already exists. Add the sample to one profile."
            } catch (e: Exception) {
                error = e.message ?: "Enrollment failed."
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(680.dp),
            shape = RoundedCornerShape(8.dp),
            color = ProdBackground,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(1.dp, ProdHairline),
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add speaker",
                        color = ProdTextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    )
                    Button(
                        onClick = { recorder.cancel(); onDismiss() },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = ProdTextTertiary,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    ) {
                        Text("x", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProdModeButton("Member", mode == EnrollMode.MEMBER) { mode = EnrollMode.MEMBER }
                    ProdModeButton("Guest", mode == EnrollMode.TEMP) { mode = EnrollMode.TEMP }
                }

                if (mode == EnrollMode.MEMBER) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ProdSurface,
                        border = BorderStroke(1.dp, ProdHairline),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (members.isEmpty()) {
                                Text(
                                    text = "No members loaded.",
                                    modifier = Modifier.padding(18.dp),
                                    color = ProdTextSecondary,
                                    fontSize = 18.sp,
                                    letterSpacing = 0.sp,
                                )
                            } else {
                                members.forEach { member ->
                                    val isSelected = selected?.userId == member.userId
                                    val label = member.displayName.ifBlank { member.email }
                                    TextButton(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(62.dp),
                                        onClick = { selected = member },
                                        colors = ButtonDefaults.textButtonColors(
                                            containerColor = if (isSelected) ProdSuccess else Color.Transparent,
                                            contentColor = if (isSelected) Color.White else ProdTextPrimary,
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelected) Color.White else Color.Transparent)
                                                    .border(
                                                        2.dp,
                                                        if (isSelected) Color.White else ProdHairline,
                                                        CircleShape,
                                                    ),
                                            )
                                            Text(
                                                text = label,
                                                modifier = Modifier.padding(start = 14.dp),
                                                color = if (isSelected) Color.White else ProdTextPrimary,
                                                fontSize = 20.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                letterSpacing = 0.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Speaker name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ProdSurface,
                    border = BorderStroke(1.dp, ProdHairline),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (captured == null) "Voice sample" else "Sample ready",
                                    color = ProdTextPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.sp,
                                )
                                Text(
                                    text = if (recording) "%.1fs".format(elapsed) else "Speak naturally for a few seconds.",
                                    color = ProdTextTertiary,
                                    fontSize = 17.sp,
                                    letterSpacing = 0.sp,
                                )
                            }
                            Button(
                                enabled = !busy,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (recording) ProdTextPrimary else ProdAccent,
                                    contentColor = Color.White,
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(132.dp),
                                onClick = {
                                    error = null
                                    if (recording) {
                                        captured = recorder.stop()
                                        recording = false
                                    } else {
                                        captured = null
                                        elapsed = 0.0
                                        level = 0f
                                        try {
                                            recorder.start()
                                            recording = true
                                        } catch (e: Exception) {
                                            error = e.message
                                        }
                                    }
                                },
                            ) {
                                Text(if (recording) "Stop" else if (captured == null) "Record" else "Re-record")
                            }
                        }
                        if (recording) {
                            LinearProgressIndicator(
                                progress = { level.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = ProdSuccess,
                                trackColor = ProdHairline,
                            )
                        }
                        captured?.quality?.let { quality ->
                            Text(
                                text = "${quality.quality.title} · ${(quality.speechRatio * 100).toInt()}% speech",
                                color = ProdTextSecondary,
                                fontSize = 16.sp,
                                letterSpacing = 0.sp,
                            )
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 17.sp) }
                status?.let { Text(it, color = ProdSuccess, fontSize = 17.sp) }

                duplicates.forEach { profile ->
                    OutlinedButton(
                        enabled = !busy,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ProdHairline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        onClick = { enroll(profile.id) },
                    ) {
                        Text("Add to ${profile.displayName}", color = ProdTextPrimary, fontSize = 18.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        enabled = !busy && captured != null && displayName.isNotEmpty() && status == null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ProdTextPrimary,
                            contentColor = Color.White,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .height(56.dp)
                            .width(126.dp),
                        onClick = { enroll(null) },
                    ) {
                        Text(if (busy) "Saving..." else "Save", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProdModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ProdTextPrimary, contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            modifier = Modifier.height(52.dp),
        ) { Text(label, fontSize = 18.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ProdTextSecondary),
            border = BorderStroke(1.dp, ProdHairline),
            modifier = Modifier.height(52.dp),
        ) { Text(label, fontSize = 18.sp) }
    }
}

/**
 * Speaker enrollment from the UMA-8: record a clip → on-device quality grade →
 * create a profile, associated with either an existing org member or a temp
 * name-only speaker.
 */
@Composable
fun SpeakerEnrollmentDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recorder = remember { EnrollmentRecorder() }

    var mode by remember { mutableStateOf(EnrollMode.MEMBER) }
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<OrgApi.Member>>(emptyList()) }
    var orgId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<OrgApi.Member?>(null) }

    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var level by remember { mutableStateOf(0f) }
    var captured by remember { mutableStateOf<EnrollmentRecorder.Result?>(null) }

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<VuedApi.SpeakerProfile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val org = OrgApi.getOrgs().firstOrNull()
            orgId = org?.id
            if (org != null) members = OrgApi.getMembers(org.id)
        } catch (_: Exception) { /* picker just stays empty; temp mode still works */ }
    }

    // Tick the elapsed counter + live level while recording.
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.secondsCaptured
            level = recorder.liveLevel
            delay(60)
        }
        level = 0f
    }

    val displayName = if (mode == EnrollMode.MEMBER) selected?.displayName.orEmpty() else name.trim()

    fun enroll(profileId: String?) {
        scope.launch {
            busy = true; error = null; status = null
            try {
                val wav = captured!!.wav
                val profile = VuedApi.enrollSpeaker(displayName, wav, profileId = profileId,
                    durationSecs = captured!!.quality.sourceSecs,
                    isOrgUser = mode == EnrollMode.MEMBER)
                val member = selected
                if (mode == EnrollMode.MEMBER && member != null && orgId != null) {
                    OrgApi.linkSpeakerProfile(orgId!!, profile.id, member.userId)
                }
                status = "Enrolled ${profile.displayName} (${profile.sampleCount} sample(s))."
                duplicates = emptyList()
            } catch (e: VuedApi.DuplicateSpeakerException) {
                duplicates = e.profiles
                error = "A speaker named \"$displayName\" exists. Add this sample to one:"
            } catch (e: Exception) {
                error = e.message ?: "Enrollment failed."
            } finally { busy = false }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Enroll speaker", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)

                // Who is this voice?
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnrollModeChip("Existing user", mode == EnrollMode.MEMBER) { mode = EnrollMode.MEMBER }
                    EnrollModeChip("Temp name", mode == EnrollMode.TEMP) { mode = EnrollMode.TEMP }
                }
                if (mode == EnrollMode.MEMBER) {
                    if (members.isEmpty()) {
                        Text("No members loaded.", fontFamily = FontFamily.Monospace)
                    } else {
                        members.forEach { m ->
                            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { selected = m }) {
                                Text(
                                    (if (selected?.userId == m.userId) "● " else "○ ") +
                                        (m.displayName.ifBlank { m.email }),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Record from the UMA-8.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!recording) {
                        Button(enabled = !busy, onClick = {
                            error = null; captured = null; elapsed = 0.0; level = 0f
                            try { recorder.start(); recording = true }
                            catch (e: Exception) { error = e.message }
                        }) { Text(if (captured == null) "Record" else "Re-record") }
                    } else {
                        Button(onClick = { captured = recorder.stop(); recording = false }) {
                            Text("Stop (%.1fs)".format(elapsed))
                        }
                    }
                    captured?.quality?.let { QualityLabel(it) }
                }

                // Live speech level while recording.
                if (recording) {
                    LinearProgressIndicator(
                        progress = { level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Recording %.1fs · speak normally".format(elapsed),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                }

                captured?.quality?.takeIf { it.quality.shouldReEnroll }?.let {
                    Text(
                        "Quality is ${it.quality.title.lowercase()} — consider re-recording with clearer speech.",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace) }
                status?.let { Text(it, fontFamily = FontFamily.Monospace) }

                // Duplicate-name resolution: add this clip to an existing profile.
                duplicates.forEach { p ->
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        onClick = { enroll(p.id) }) {
                        Text("Add sample to ${p.displayName} (${p.sampleCount})", fontFamily = FontFamily.Monospace)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && captured != null && displayName.isNotEmpty() && status == null,
                        onClick = { enroll(null) },
                    ) { Text(if (busy) "Saving…" else "Save") }
                    OutlinedButton(onClick = { recorder.cancel(); onDismiss() }) {
                        Text(if (status != null) "Done" else "Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun EnrollModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun QualityLabel(q: EnrollmentQualityResult) {
    Text(
        "${q.quality.title} · ${(q.speechRatio * 100).toInt()}% speech",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
