package com.recall.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recall.app.capture.PendingShare
import com.recall.app.ui.AppViewModel
import com.recall.app.ui.RecallRoot
import com.recall.app.ui.RecallTheme
import javax.crypto.Cipher

class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecallTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.configured, state.locked) {
                    if (state.configured && !state.locked) {
                        PendingShare.take()?.let(viewModel::savePendingShare)
                    }
                }
                LaunchedEffect(state.biometricEnrollRequest) {
                    if (state.biometricEnrollRequest > 0L) enrollBiometric()
                }
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri -> uri?.let(viewModel::exportTo) }
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(viewModel::importFrom) }

                RecallRoot(
                    state = state,
                    viewModel = viewModel,
                    onBiometric = ::authenticate,
                    onExport = { exportLauncher.launch("recall-backup-${System.currentTimeMillis()}.zip") },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onRequestScreenshotPermissions = ::requestScreenshotPermissions,
                    onOpenUsageAccess = ::openUsageAccess,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Session.isUnlocked()) {
            PendingShare.take()?.let(viewModel::savePendingShare)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun authenticate() {
        val cipher: Cipher = viewModel.prepareBiometricCipher() ?: return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher ?: return
                    viewModel.unlockWithBiometric(authorized)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // The passphrase field remains available.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Recall")
            .setSubtitle("Authenticate to unwrap your vault key")
            .setNegativeButtonText("Use PIN or passphrase")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun enrollBiometric() {
        viewModel.consumeBiometricEnrollmentRequest()
        val cipher = viewModel.prepareBiometricEnrollmentCipher()
        if (cipher == null) {
            viewModel.cancelBiometricEnrollment()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) {
                        viewModel.cancelBiometricEnrollment()
                    } else {
                        viewModel.completeBiometricEnrollment(authorized)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.cancelBiometricEnrollment()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable fingerprint unlock")
            .setSubtitle("Authenticate once to wrap your vault key")
            .setNegativeButtonText("Not now")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun requestScreenshotPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
    }

    private fun openUsageAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }
}
