package com.recall.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.recall.app.CaptureEditorActivity

@Composable
fun RecallRoot(
    state: RecallUiState,
    viewModel: AppViewModel,
    onBiometric: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRequestScreenshotPermissions: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    val context = LocalContext.current
    val openCapture: (String) -> Unit = { id ->
        context.startActivity(
            Intent(context, CaptureEditorActivity::class.java)
                .putExtra(CaptureEditorActivity.EXTRA_CAPTURE_ID, id),
        )
    }
    val openNewCapture: () -> Unit = {
        context.startActivity(
            Intent(context, CaptureEditorActivity::class.java)
                .putExtra(CaptureEditorActivity.EXTRA_NEW_CAPTURE, true),
        )
    }

    AuroraBackground {
        when {
            !state.configured -> SetupScreen(
                state = state,
                onSetup = viewModel::setup,
                onClearMessages = viewModel::clearMessages,
            )
            state.locked -> UnlockScreen(
                state = state,
                onUnlock = viewModel::unlock,
                onPinUnlock = viewModel::unlockWithPin,
                onBiometric = onBiometric,
                onClearMessages = viewModel::clearMessages,
            )
            state.settingsOpen -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                onExport = onExport,
                onImport = onImport,
                onRequestScreenshotPermissions = onRequestScreenshotPermissions,
                onOpenUsageAccess = onOpenUsageAccess,
            )
            state.archivedOpen -> ArchivedScreen(
                state = state,
                viewModel = viewModel,
                onOpenCapture = openCapture,
            )
            else -> MainScreen(
                state = state,
                viewModel = viewModel,
                onOpenCapture = openCapture,
                onNewCapture = openNewCapture,
            )
        }
    }
}

@Composable
private fun SetupScreen(
    state: RecallUiState,
    onSetup: (String, String, Boolean) -> Unit,
    onClearMessages: () -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var biometric by rememberSaveable { mutableStateOf(state.biometricAvailable) }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        localError = when {
            passphrase.length < 8 -> "Use at least 8 characters."
            passphrase != confirm -> "The two passphrases do not match."
            pin.length != 4 || pin.any { !it.isDigit() } -> "Choose a 4-digit PIN."
            pin != confirmPin -> "The two PINs do not match."
            else -> null
        }
        if (localError == null) {
            onSetup(passphrase, pin, biometric && state.biometricAvailable)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RecallMark(size = 86.dp)
        Spacer(Modifier.height(18.dp))
        SectionEyebrow(icon = Icons.Outlined.Shield, text = "Private by default")
        Spacer(Modifier.height(9.dp))
        Text(
            text = "Create your memory",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "One passphrase encrypts everything. Searchable in a second, readable only by you.",
            style = MaterialTheme.typography.bodyLarge,
            color = RecallMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        GlassCard(padding = 18.dp) {
            Text("Your passphrase", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                "Use 4–6 diceware words. There is no account and no recovery.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(13.dp))
            RecallTextField(
                value = passphrase,
                onValueChange = { passphrase = it; localError = null },
                label = "Data passphrase",
                icon = Icons.Outlined.Key,
                placeholder = "4–6 diceware words",
                password = !showPassphrase,
                keyboardType = KeyboardType.Password,
                trailingIcon = {
                    Icon(
                        imageVector = if (showPassphrase) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassphrase) "Hide passphrase" else "Show passphrase",
                        tint = RecallFaint,
                        modifier = Modifier
                            .clickable { showPassphrase = !showPassphrase }
                            .padding(6.dp)
                            .size(20.dp),
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            RecallTextField(
                value = confirm,
                onValueChange = { confirm = it; localError = null },
                label = "Confirm passphrase",
                icon = Icons.Outlined.CheckCircle,
                password = true,
                keyboardType = KeyboardType.Password,
            )

            Spacer(Modifier.height(18.dp))
            SoftDivider()
            Spacer(Modifier.height(16.dp))
            Text("Unlock preferences", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                "Fingerprint is the fastest way in. The PIN is a local fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )

            if (state.biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                FingerprintToggle(
                    checked = biometric,
                    onCheckedChange = { biometric = it },
                )
            } else {
                Spacer(Modifier.height(12.dp))
                InfoRow(
                    icon = Icons.Outlined.Info,
                    title = "Fingerprint not available",
                    body = "Enroll a fingerprint in Android settings to enable it later.",
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("4-digit PIN", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                "The PIN is a local convenience. Your passphrase still protects the vault and sync.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecallTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                        localError = null
                    },
                    label = "PIN",
                    modifier = Modifier.weight(1f),
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                )
                RecallTextField(
                    value = confirmPin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) confirmPin = value
                        localError = null
                    },
                    label = "Confirm",
                    modifier = Modifier.weight(1f),
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                )
            }

            Spacer(Modifier.height(18.dp))
            RecallPrimaryButton(
                text = "Create encrypted vault",
                icon = Icons.Outlined.ArrowForward,
                onClick = ::submit,
                loading = state.busy,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = RecallMint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Encrypted sync is on. The relay stores ciphertext and timestamps only.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallSuccessText,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "No password reset. If you lose the passphrase, the data is gone. The same passphrase on another device joins the same encrypted stream.",
            style = MaterialTheme.typography.bodySmall,
            color = RecallFaint,
            textAlign = TextAlign.Center,
        )

        MessageCard(
            error = state.error ?: localError,
            notice = state.notice,
            onDismiss = {
                localError = null
                onClearMessages()
            },
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun UnlockScreen(
    state: RecallUiState,
    onUnlock: (String) -> Unit,
    onPinUnlock: (String) -> Unit,
    onBiometric: () -> Unit,
    onClearMessages: () -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var usePassphrase by rememberSaveable { mutableStateOf(!state.pinConfigured) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RecallMark(size = 90.dp)
        Spacer(Modifier.height(18.dp))
        SectionEyebrow(icon = Icons.Outlined.Lock, text = "Welcome back")
        Spacer(Modifier.height(9.dp))
        Text(
            text = "Unlock your memory",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Your captures are encrypted on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = RecallMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (state.biometricEnabled) {
            FingerprintUnlockCard(
                enabled = !state.busy,
                onClick = onBiometric,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SoftDivider(modifier = Modifier.weight(1f))
                Text(
                    "or use your PIN / passphrase",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallFaint,
                )
                SoftDivider(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }

        GlassCard(padding = 18.dp) {
            if (state.pinConfigured && !usePassphrase) {
                Text("Quick unlock", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Enter your 4-digit PIN to open the vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
                Spacer(Modifier.height(12.dp))
                RecallTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                        onClearMessages()
                    },
                    label = "4-digit PIN",
                    icon = Icons.Outlined.Key,
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                )
                Spacer(Modifier.height(14.dp))
                RecallPrimaryButton(
                    text = "Unlock with PIN",
                    icon = Icons.Outlined.ArrowForward,
                    onClick = { onPinUnlock(pin) },
                    enabled = pin.length == 4,
                    loading = state.busy,
                )
            } else {
                Text("Use your passphrase", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "The passphrase never leaves this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
                Spacer(Modifier.height(12.dp))
                RecallTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; onClearMessages() },
                    label = "Data passphrase",
                    icon = Icons.Outlined.Key,
                    password = true,
                    keyboardType = KeyboardType.Password,
                )
                Spacer(Modifier.height(14.dp))
                RecallPrimaryButton(
                    text = "Unlock vault",
                    icon = Icons.Outlined.ArrowForward,
                    onClick = { onUnlock(passphrase) },
                    enabled = passphrase.isNotEmpty(),
                    loading = state.busy,
                )
            }

            if (state.pinConfigured) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        usePassphrase = !usePassphrase
                        onClearMessages()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        if (usePassphrase) "Use 4-digit PIN instead" else "Use passphrase instead",
                        style = MaterialTheme.typography.labelMedium,
                        color = RecallCyan,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        StatusChip(
            text = if (state.localOnly) "Local-only vault" else "Relay sync on",
            online = !state.localOnly,
        )
        if (!state.localOnly && !state.relayUrl.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.relayUrl.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
                textAlign = TextAlign.Center,
            )
        }
        MessageCard(state.error, state.notice, onClearMessages)
    }
}

@Composable
private fun FingerprintToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RecallViolet.copy(alpha = 0.10f))
            .border(1.dp, RecallViolet.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = Icons.Outlined.Fingerprint, tint = RecallCyan, size = 42.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleSmall)
            Text(
                "Recommended. Wraps your passphrase with a biometric-bound key.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RecallOnAccent,
                checkedTrackColor = RecallMint,
                uncheckedThumbColor = RecallMuted,
                uncheckedTrackColor = RecallBorder,
                uncheckedBorderColor = RecallBorderStrong,
            ),
        )
    }
}

@Composable
private fun FingerprintUnlockCard(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        RecallViolet.copy(alpha = 0.24f),
                        RecallCyan.copy(alpha = 0.14f),
                    ),
                ),
            )
            .border(1.dp, RecallCyan.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(RecallBorder),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Fingerprint,
                contentDescription = null,
                tint = RecallText,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap to authenticate. Your fingerprint never leaves the sensor.",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ArrowForward,
            contentDescription = null,
            tint = RecallCyan,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun MessageCard(
    error: String?,
    notice: String?,
    onDismiss: () -> Unit,
) {
    val message = error ?: notice ?: return
    val isError = error != null
    val tint = if (isError) RecallDanger else RecallCyan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else RecallText,
        )
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = RecallFaint,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(6.dp)
                .size(17.dp),
        )
    }
}
