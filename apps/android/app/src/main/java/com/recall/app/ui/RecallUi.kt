package com.recall.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !state.configured -> SetupScreen(state, viewModel::setup, viewModel::clearMessages)
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
            else -> MainScreen(state, viewModel)
        }
        state.detail?.let { detail ->
            DetailDialog(detail = detail, onDismiss = viewModel::closeDetail)
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
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text("Recall", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your encrypted memory. Searchable in a second, readable only by you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it; localError = null },
            label = { Text("Passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; localError = null },
            label = { Text("Confirm passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text("Daily unlock", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                localError = null
            },
            label = { Text("4-digit PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { value ->
                if (value.length <= 4 && value.all { it.isDigit() }) confirmPin = value
                localError = null
            },
            label = { Text("Confirm PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "The PIN is a local convenience. Your passphrase still protects the vault and sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Encrypted sync is on. The relay stores ciphertext and timestamps only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (state.biometricAvailable) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unlock with fingerprint", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Recommended. Wraps your passphrase with a biometric-bound Keystore key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = biometric, onCheckedChange = { biometric = it })
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = ::submit,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Create my vault")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "There is no password reset. If you lose the passphrase, the data is gone. " +
                "The same passphrase on another device joins the same encrypted stream.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MessageCard(state.error ?: localError, state.notice, onClearMessages)
        Spacer(Modifier.height(24.dp))
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
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Recall", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Locked",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        if (state.pinConfigured && !usePassphrase) {
            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it.isDigit() }) pin = value
                    onClearMessages()
                },
                label = { Text("4-digit PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onPinUnlock(pin) },
                enabled = !state.busy && pin.length == 4,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
        } else {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; onClearMessages() },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onUnlock(passphrase) },
                enabled = !state.busy && passphrase.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
        }

        if (state.biometricEnabled) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBiometric,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock with fingerprint")
            }
        }

        if (state.pinConfigured) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    usePassphrase = !usePassphrase
                    onClearMessages()
                },
            ) {
                Text(if (usePassphrase) "Use 4-digit PIN" else "Use passphrase instead")
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            if (state.localOnly) "Local-only vault" else "Relay: ${state.relayUrl.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        MessageCard(state.error, state.notice, onClearMessages)
    }
}

@Composable
fun MessageCard(error: String?, notice: String?, onDismiss: () -> Unit) {
    val message = error ?: notice ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
