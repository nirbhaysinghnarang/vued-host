package com.nsn8.vued.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.net.RoomConfig

/**
 * Lets the tablet assign itself to one of the org's rooms. Fetches the account's
 * org and its rooms, persists the chosen room locally (RoomConfig). Tagged onto
 * every uploaded slice thereafter.
 */
@Composable
fun RoomPickerDialog(onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    var rooms by remember { mutableStateOf<List<OrgApi.Room>>(emptyList()) }
    var orgId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val org = OrgApi.getOrgs().firstOrNull()
            if (org == null) {
                error = "No organization for this account."
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Assign this tablet to a room",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                when {
                    loading -> Text("Loading…", fontFamily = FontFamily.Monospace)
                    error != null -> Text("Error: $error", fontFamily = FontFamily.Monospace)
                    rooms.isEmpty() -> Text(
                        "No rooms yet. Create one in the dashboard.",
                        fontFamily = FontFamily.Monospace,
                    )
                    else -> rooms.forEach { room ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                RoomConfig.set(context, room.id, room.displayName, orgId ?: "", room.microphoneId)
                                onPicked(room.displayName)
                                onDismiss()
                            },
                        ) {
                            Text(
                                "${room.displayName}  (${room.microphoneId})",
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}
