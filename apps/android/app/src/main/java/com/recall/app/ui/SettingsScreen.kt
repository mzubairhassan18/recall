package com.recall.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.recall.app.capture.UsageAccess
import com.recall.app.config.AppConfig

@Composable
fun SettingsScreen(
    state: RecallUiState,
    viewModel: AppViewModel,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRequestScreenshotPermissions: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    val context = LocalContext.current
    var packageName by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::closeSettings) { Text("Back") }
        }

        MessageCard(state.error, state.notice, viewModel::clearMessages)

        SettingsSection("Sync") {
            Text(
                if (state.localOnly) "Local-only vault — no relay configured." else state.relayUrl.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::sync,
                    enabled = !state.localOnly && !state.syncing,
                ) { Text(if (state.syncing) "Syncing…" else "Sync now") }
            }
            if (state.lastSyncAt > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last sync ${relativeTime(state.lastSyncAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection("Security") {
            if (state.biometricAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Passphrase wrapped by an auth-bound Keystore key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) viewModel.enableBiometric() else viewModel.disableBiometric()
                        },
                    )
                }
            } else {
                Text(
                    "No strong biometric enrolled on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("4-digit PIN", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.pinConfigured) {
                    "A PIN is set. You can change it below."
                } else {
                    "Set a PIN for quick daily unlock. Your passphrase is still the encryption key."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                    pinError = null
                },
                label = { Text("New 4-digit PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it.isDigit() }) confirmPin = value
                    pinError = null
                },
                label = { Text("Confirm PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (pinError != null) {
                Spacer(Modifier.height(4.dp))
                Text(pinError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    when {
                        pin.length != 4 -> pinError = "Choose a 4-digit PIN."
                        pin != confirmPin -> pinError = "The two PINs do not match."
                        else -> {
                            viewModel.setPin(pin)
                            pin = ""
                            confirmPin = ""
                            pinError = null
                        }
                    }
                },
                enabled = pin.length == 4 && pin == confirmPin,
            ) {
                Text(if (state.pinConfigured) "Change PIN" else "Set PIN")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = viewModel::lock) { Text("Lock now") }
            Spacer(Modifier.height(6.dp))
            Text(
                "The passphrase and derived key are kept in memory only while unlocked. " +
                    "The PIN is a local gate; the passphrase remains the encryption key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection("Screenshot proposals") {
            Text(
                "Off by default. Screenshots are proposed from MediaStore; nothing is uploaded in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AppConfig.MODE_OFF to "Off",
                    AppConfig.MODE_ASK to "Ask",
                    AppConfig.MODE_HOLD to "Hold",
                    AppConfig.MODE_ALLOWLIST to "Allowlist",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = state.screenshotMode == mode,
                        onClick = { viewModel.setScreenshotMode(mode) },
                        label = { Text(label) },
                    )
                }
            }

            if (state.screenshotMode != AppConfig.MODE_OFF) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequestScreenshotPermissions) {
                    Text("Grant media + notification access")
                }
            }

            if (state.screenshotMode == AppConfig.MODE_ALLOWLIST) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (UsageAccess.hasAccess(context)) {
                        "Usage access granted — foreground app is checked against the allowlist."
                    } else {
                        "Usage access is needed to know which app was in front."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onOpenUsageAccess) { Text("Open usage access settings") }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package name") },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        viewModel.addAllowlist(packageName)
                        packageName = ""
                    }) { Text("Add") }
                }
                Spacer(Modifier.height(8.dp))
                if (state.allowlist.isEmpty()) {
                    Text(
                        "No packages allowlisted yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.allowlist.sorted().forEach { packageId ->
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.removeAllowlist(packageId) },
                                label = { Text("$packageId  ×") },
                            )
                        }
                    }
                }
            }
        }

        SettingsSection("Held screenshots") {
            Text(
                "${state.pendingScreenshots} screenshot(s) waiting in the 60-minute inbox.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::savePendingScreenshots,
                    enabled = state.pendingScreenshots > 0,
                ) { Text("Save all") }
                OutlinedButton(
                    onClick = viewModel::discardPendingScreenshots,
                    enabled = state.pendingScreenshots > 0,
                ) { Text("Discard") }
            }
        }

        SettingsSection("Encrypted backup") {
            Text(
                "Exports a zip of encrypted envelopes plus a manifest. The passphrase is still required to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport) { Text("Export") }
                OutlinedButton(onClick = onImport) { Text("Import") }
            }
        }

        SettingsSection("About") {
            Text("Recall 1.0.0", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "AES-256-GCM · PBKDF2-HMAC-SHA256 600,000 · SQLCipher + FTS5 · " +
                    "relay stores ciphertext and timestamps only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
