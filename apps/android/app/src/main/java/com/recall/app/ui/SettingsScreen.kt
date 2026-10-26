package com.recall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecallIconButton(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = viewModel::closeSettings,
            )
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            RecallMark(size = 42.dp)
        }

        MessageCard(state.error, state.notice, viewModel::clearMessages)

        SettingsCard(
            title = "Sync",
            icon = Icons.Outlined.Sync,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusChip(
                    text = if (state.localOnly) "Local-only" else "Relay connected",
                    online = !state.localOnly,
                )
                if (state.lastSyncAt > 0) {
                    Text(
                        "Last sync ${relativeTime(state.lastSyncAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = RecallFaint,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.localOnly) "No relay configured. Everything stays on this device."
                else state.relayUrl.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    text = if (state.syncing) "Syncing…" else "Sync now",
                    icon = Icons.Outlined.Sync,
                    onClick = viewModel::sync,
                    enabled = !state.localOnly && !state.syncing,
                    primary = true,
                )
            }
        }

        SettingsCard(
            title = "Security",
            icon = Icons.Outlined.Shield,
        ) {
            if (state.biometricAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(RecallViolet.copy(alpha = 0.08f))
                        .border(1.dp, RecallViolet.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    IconBadge(icon = Icons.Outlined.Fingerprint, tint = RecallCyan, size = 40.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint unlock", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Fastest way in. Wrapped by an auth-bound Keystore key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RecallMuted,
                        )
                    }
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) viewModel.enableBiometric() else viewModel.disableBiometric()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = RecallInk950,
                            checkedTrackColor = RecallMint,
                            uncheckedThumbColor = RecallMuted,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
                        ),
                    )
                }
            } else {
                InfoRow(
                    icon = Icons.Outlined.Info,
                    title = "Fingerprint not available",
                    body = "Enroll a fingerprint in Android settings, then return here.",
                )
            }

            Spacer(Modifier.height(16.dp))
            SoftDivider()
            Spacer(Modifier.height(16.dp))
            Text("4-digit PIN", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                if (state.pinConfigured) {
                    "A PIN is set. You can change it below."
                } else {
                    "Set a PIN for quick daily unlock. Your passphrase is still the encryption key."
                },
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecallTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                        pinError = null
                    },
                    label = "New PIN",
                    modifier = Modifier.weight(1f),
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                )
                RecallTextField(
                    value = confirmPin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) confirmPin = value
                        pinError = null
                    },
                    label = "Confirm",
                    modifier = Modifier.weight(1f),
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                )
            }
            if (pinError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    pinError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallDanger,
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsActionButton(
                text = if (state.pinConfigured) "Change PIN" else "Set PIN",
                icon = Icons.Outlined.Key,
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
            )

            Spacer(Modifier.height(16.dp))
            SoftDivider()
            Spacer(Modifier.height(16.dp))
            SettingsActionButton(
                text = "Lock now",
                icon = Icons.Outlined.Lock,
                onClick = viewModel::lock,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The passphrase and derived key stay in memory only while unlocked. " +
                    "The PIN is a local gate; the passphrase remains the encryption key.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallFaint,
            )
        }

        SettingsCard(
            title = "Screenshot proposals",
            icon = Icons.Outlined.Photo,
        ) {
            Text(
                "Off by default. Screenshots are proposed from MediaStore; nothing is uploaded in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AppConfig.MODE_OFF to "Off",
                    AppConfig.MODE_ASK to "Ask",
                    AppConfig.MODE_HOLD to "Hold",
                    AppConfig.MODE_ALLOWLIST to "Allowlist",
                ).forEach { (mode, label) ->
                    SettingsChip(
                        selected = state.screenshotMode == mode,
                        label = label,
                        onClick = { viewModel.setScreenshotMode(mode) },
                    )
                }
            }

            if (state.screenshotMode != AppConfig.MODE_OFF) {
                Spacer(Modifier.height(10.dp))
                SettingsActionButton(
                    text = "Grant media + notification access",
                    icon = Icons.Outlined.CheckCircle,
                    onClick = onRequestScreenshotPermissions,
                )
            }

            if (state.screenshotMode == AppConfig.MODE_ALLOWLIST) {
                Spacer(Modifier.height(14.dp))
                SoftDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    if (UsageAccess.hasAccess(context)) {
                        "Usage access granted. The foreground app is checked against the allowlist."
                    } else {
                        "Usage access is needed to know which app was in front."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionButton(
                    text = "Open usage access settings",
                    icon = Icons.Outlined.Info,
                    onClick = onOpenUsageAccess,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecallTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = "Package name",
                        placeholder = "com.example.app",
                        modifier = Modifier.weight(1f),
                    )
                    RecallIconButton(
                        icon = Icons.Outlined.Add,
                        contentDescription = "Add package",
                        onClick = {
                            viewModel.addAllowlist(packageName)
                            packageName = ""
                        },
                        enabled = packageName.isNotBlank(),
                        tint = RecallCyan,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (state.allowlist.isEmpty()) {
                    Text(
                        "No packages allowlisted yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RecallFaint,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.allowlist.sorted().forEach { packageId ->
                            SettingsChip(
                                selected = true,
                                label = "$packageId  ×",
                                onClick = { viewModel.removeAllowlist(packageId) },
                            )
                        }
                    }
                }
            }
        }

        SettingsCard(
            title = "Held screenshots",
            icon = Icons.Outlined.Photo,
        ) {
            Text(
                "${state.pendingScreenshots} screenshot(s) waiting in the 60-minute inbox.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    text = "Save all",
                    icon = Icons.Outlined.Save,
                    onClick = viewModel::savePendingScreenshots,
                    enabled = state.pendingScreenshots > 0,
                    primary = true,
                )
                SettingsActionButton(
                    text = "Discard",
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = viewModel::discardPendingScreenshots,
                    enabled = state.pendingScreenshots > 0,
                )
            }
        }

        SettingsCard(
            title = "Encrypted backup",
            icon = Icons.Outlined.Archive,
        ) {
            Text(
                "Exports a zip of encrypted envelopes plus a manifest. The passphrase is still required to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    text = "Export",
                    icon = Icons.Outlined.Archive,
                    onClick = onExport,
                )
                SettingsActionButton(
                    text = "Import",
                    icon = Icons.Outlined.Archive,
                    onClick = onImport,
                )
            }
        }

        SettingsCard(
            title = "About",
            icon = Icons.Outlined.Info,
        ) {
            Text("Recall 1.0.0", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "AES-256-GCM · PBKDF2-HMAC-SHA256 600,000 · SQLCipher + FTS5. " +
                    "The relay stores ciphertext and timestamps only.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(top = 14.dp),
        padding = 16.dp,
    ) {
        SectionEyebrow(icon = icon, text = title)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingsChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) RecallViolet.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.045f))
            .border(
                1.dp,
                if (selected) RecallViolet.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) RecallText else RecallMuted,
        )
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(13.dp)
    val background = if (primary) {
        Brush.horizontalGradient(listOf(RecallViolet, RecallCyan))
    } else {
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.055f)))
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                if (primary) Color.Transparent else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (primary) RecallInk950 else RecallCyan,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) RecallInk950 else RecallText,
        )
    }
}
