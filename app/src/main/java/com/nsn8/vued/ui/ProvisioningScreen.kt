package com.nsn8.vued.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nsn8.vued.crypto.VuedCrypto
import com.nsn8.vued.net.VuedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { LOADING, NEW, RECOVER }

/**
 * The second secret: the user's **encryption passphrase**. On a fresh account this
 * sets it up (generate keypair → upload public key + passphrase-wrapped private key).
 * On a new device for an existing account it recovers the private key from the vault.
 * The passphrase never leaves the device.
 */
@Composable
fun ProvisioningScreen(onProvisioned: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Mode.LOADING) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // A vault on the server means this account already has a keypair → recover.
        mode = try {
            if (VuedApi.fetchVault() != null) Mode.RECOVER else Mode.NEW
        } catch (_: Throwable) {
            Mode.NEW
        }
    }

    fun submit() {
        error = null
        if (mode == Mode.NEW && passphrase != confirm) {
            error = "Passphrases don't match"
            return
        }
        busy = true
        scope.launch {
            try {
                if (mode == Mode.RECOVER) {
                    val wrapped = VuedApi.fetchVault() ?: throw IllegalStateException("No vault found")
                    withContext(Dispatchers.Default) { VuedCrypto.recover(context, passphrase, wrapped) }
                    // Best-effort re-assert — the server already has the public key.
                    runCatching { VuedCrypto.publicKeysetB64(context)?.let { VuedApi.uploadPublicKey(it) } }
                } else {
                    val provision = withContext(Dispatchers.Default) { VuedCrypto.provision(context, passphrase) }
                    // Uploads are essential: if they fail, ROLL BACK the local key so we
                    // don't strand the user "provisioned" while the server has nothing.
                    try {
                        VuedApi.uploadPublicKey(provision.publicKeysetB64)
                        VuedApi.uploadVault(provision.wrappedPrivateKey)
                    } catch (e: Throwable) {
                        VuedCrypto.clear(context)
                        throw e
                    }
                }
                onProvisioned()
            } catch (e: Throwable) {
                error = when (mode) {
                    Mode.RECOVER -> "Wrong passphrase, or recovery failed"
                    else -> e.message ?: "Setup failed"
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (mode) {
            Mode.LOADING -> CircularProgressIndicator()
            else -> {
                Text(
                    text = if (mode == Mode.RECOVER) "Unlock your data" else "Set an encryption passphrase",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = if (mode == Mode.RECOVER)
                        "Enter your passphrase to decrypt your recordings on this device."
                    else
                        "This protects your recordings end-to-end. It never leaves this device and cannot be recovered if lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode == Mode.NEW) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = Color(0xFF9B1C1C), style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { submit() },
                    enabled = !busy && passphrase.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Working…")
                    } else {
                        Text(if (mode == Mode.RECOVER) "Unlock" else "Set up encryption")
                    }
                }
            }
        }
    }
}
