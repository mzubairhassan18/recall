package com.recall.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recall.app.Session
import com.recall.app.capture.CaptureSaver
import com.recall.app.capture.PendingShare
import com.recall.app.capture.ScreenshotNotifier
import com.recall.app.capture.ScreenshotScanWorker
import com.recall.app.capture.payloadForText
import com.recall.app.config.AppConfig
import com.recall.app.config.RelayDefaults
import com.recall.app.data.CaptureDetail
import com.recall.app.security.BiometricVault
import com.recall.app.security.PinCheck
import com.recall.app.security.PinVault
import com.recall.app.sync.RelayClient
import com.recall.app.sync.SyncEngine
import com.recall.app.sync.SyncWorker
import com.recall.app.widget.RecallWidgetProvider
import com.recall.core.RecallCrypto
import com.recall.core.SearchResult
import com.recall.core.SynonymIndex
import com.recall.core.buildSynonymIndex
import com.recall.core.parseSynonymDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.crypto.Cipher

data class RecallUiState(
    val configured: Boolean = false,
    val locked: Boolean = true,
    val busy: Boolean = false,
    val count: Int = 0,
    val query: String = "",
    val typeFilter: String? = null,
    val domain: String = "",
    val results: List<SearchResult> = emptyList(),
    val detail: CaptureDetail? = null,
    val error: String? = null,
    val notice: String? = null,
    val settingsOpen: Boolean = false,
    val localOnly: Boolean = true,
    val relayUrl: String? = null,
    val pinConfigured: Boolean = false,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricEnrollRequest: Long = 0L,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0L,
    val pendingScreenshots: Int = 0,
    val screenshotMode: String = AppConfig.MODE_OFF,
    val allowlist: Set<String> = emptySet(),
    val timelineView: Boolean = false,
    val sortDescending: Boolean = true,
    val favoritesOnly: Boolean = false,
    val selectedTag: String? = null,
    val allTags: List<String> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val archivedOpen: Boolean = false,
    val archivedResults: List<SearchResult> = emptyList(),
    val archivedCount: Int = 0,
    val composerOpen: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val config = AppConfig(application)
    private val vault = BiometricVault(application)
    private val pinVault = PinVault(application)
    private val synonyms: SynonymIndex = loadSynonyms(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RecallUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    private fun initialState(): RecallUiState {
        val stored = config.load()
        return RecallUiState(
            configured = config.isConfigured,
            locked = !Session.isUnlocked(),
            localOnly = stored.localOnly,
            relayUrl = stored.relayUrl,
            pinConfigured = pinVault.hasPin,
            biometricAvailable = vault.isAvailable(getApplication()),
            biometricEnabled = config.biometricEnabled && vault.hasCredential(),
            lastSyncAt = config.lastSyncAt,
            pendingScreenshots = config.pendingScreenshots().size,
            screenshotMode = config.screenshotMode,
            allowlist = config.allowlist,
        )
    }

    fun setup(passphraseText: String, pinText: String, enableBiometric: Boolean) {
        if (passphraseText.length < 8) {
            _state.update { it.copy(error = "Use a passphrase of at least 8 characters.") }
            return
        }
        if (pinText.length != PinVault.PIN_LENGTH || pinText.any { !it.isDigit() }) {
            _state.update { it.copy(error = "Choose a 4-digit PIN.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            val app = getApplication<Application>()
            val pass = passphraseText.toCharArray()
            val pin = pinText.toCharArray()
            var rawKey: ByteArray? = null
            var opened = false
            var pinStored = false
            try {
                val username = withContext(Dispatchers.Default) { RecallCrypto.deriveSyncUsername(pass) }
                val registration = RelayClient(RelayDefaults.URL, RelayDefaults.SECRET).register(username)
                val saltB64 = registration.salt
                val userId = registration.userId
                val salt = RecallCrypto.base64Decode(saltB64)
                rawKey = withContext(Dispatchers.Default) { RecallCrypto.deriveKeyBytes(pass, salt) }
                val fingerprint = RecallCrypto.keyFingerprint(rawKey)
                withContext(Dispatchers.IO) { Session.open(app, pass, rawKey) }
                opened = true
                pinStored = withContext(Dispatchers.Default) { pinVault.setPin(pin, pass) }
                config.saveIdentity(
                    username = username,
                    userId = userId,
                    salt = saltB64,
                    fingerprint = fingerprint,
                    relayUrl = RelayDefaults.URL,
                    localOnly = false,
                )
                config.relaySecret = RelayDefaults.SECRET

                // Auth-bound Keystore encryption needs a BiometricPrompt, so enrollment is
                // requested after the vault is open. The state request is observed by MainActivity.
                config.biometricEnabled = false
                val shouldEnroll = enableBiometric && _state.value.biometricAvailable

                _state.update {
                    it.copy(
                        configured = true,
                        locked = false,
                        busy = false,
                        localOnly = false,
                        relayUrl = RelayDefaults.URL,
                        pinConfigured = pinStored,
                        biometricEnabled = false,
                        notice = when {
                            !pinStored ->
                                "Vault created. PIN storage was unavailable; use your passphrase."
                            shouldEnroll ->
                                "Recall is ready. Confirm your fingerprint to enable unlock."
                            else -> "Recall is ready."
                        },
                    )
                }
                SyncWorker.schedule(app)
                ScreenshotScanWorker.schedule(app)
                refresh()
                sync()
                if (shouldEnroll) requestBiometricEnrollment()
            } catch (error: Exception) {
                if (opened) Session.clear()
                if (pinStored) pinVault.clear()
                _state.update { it.copy(busy = false, error = error.message ?: "setup-failed") }
            } finally {
                pass.fill('\u0000')
                pin.fill('\u0000')
                rawKey?.fill(0)
            }
        }
    }

    fun unlock(passphraseText: String) {
        if (passphraseText.isEmpty()) {
            _state.update { it.copy(error = "Enter your passphrase.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val pass = passphraseText.toCharArray()
            try {
                unlockInternal(pass)
            } catch (error: Exception) {
                Session.clear()
                _state.update { it.copy(busy = false, error = error.message ?: "unlock-failed") }
            } finally {
                pass.fill('\u0000')
            }
        }
    }

    fun unlockWithPin(pinText: String) {
        if (pinText.length != PinVault.PIN_LENGTH || pinText.any { !it.isDigit() }) {
            _state.update { it.copy(error = "Enter your 4-digit PIN.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val pin = pinText.toCharArray()
            try {
                when (val result = withContext(Dispatchers.Default) { pinVault.checkPin(pin) }) {
                    is PinCheck.Success -> {
                        val pass = result.passphrase
                        try {
                            unlockInternal(pass)
                        } finally {
                            pass.fill('\u0000')
                        }
                    }
                    is PinCheck.Wrong -> {
                        val message = if (result.retryAfterMs > 0) {
                            "Too many attempts. Try again in ${seconds(result.retryAfterMs)}s."
                        } else {
                            "Wrong PIN. ${result.attemptsLeft} attempt(s) left."
                        }
                        _state.update { it.copy(busy = false, error = message) }
                    }
                    PinCheck.Unavailable -> _state.update {
                        it.copy(busy = false, error = "PIN unlock is unavailable. Use your passphrase.")
                    }
                    PinCheck.NotConfigured -> _state.update {
                        it.copy(busy = false, error = "No PIN is configured. Use your passphrase.")
                    }
                }
            } catch (error: Exception) {
                Session.clear()
                _state.update { it.copy(busy = false, error = error.message ?: "unlock-failed") }
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    private suspend fun unlockInternal(pass: CharArray) {
        val app = getApplication<Application>()
        val stored = config.load()
        if (stored.salt == null || stored.fingerprint == null) {
            _state.update { it.copy(busy = false, error = "This device is not set up.") }
            return
        }
        val salt = RecallCrypto.base64Decode(stored.salt)
        val rawKey = withContext(Dispatchers.Default) { RecallCrypto.deriveKeyBytes(pass, salt) }
        try {
            if (RecallCrypto.keyFingerprint(rawKey) != stored.fingerprint) {
                _state.update { it.copy(busy = false, error = "Wrong passphrase.") }
                return
            }
            withContext(Dispatchers.IO) { Session.open(app, pass, rawKey) }
            _state.update {
                it.copy(
                    locked = false,
                    busy = false,
                    error = null,
                    localOnly = stored.localOnly,
                    relayUrl = stored.relayUrl,
                    pinConfigured = pinVault.hasPin,
                    biometricEnabled = config.biometricEnabled && vault.hasCredential(),
                    pendingScreenshots = config.pendingScreenshots().size,
                )
            }
            RecallWidgetProvider.refresh(app)
            refresh()
            if (!stored.localOnly) sync()
        } finally {
            rawKey.fill(0)
        }
    }

    private fun seconds(ms: Long): Long = ((ms + 999L) / 1000L).coerceAtLeast(1L)

    fun unlockWithBiometric(cipher: Cipher) {
        val passphrase = vault.decrypt(cipher)
        if (passphrase == null) {
            _state.update { it.copy(error = "Biometric unlock failed. Use your passphrase.") }
            return
        }
        val text = passphrase.concatToString()
        passphrase.fill('\u0000')
        unlock(text)
    }

    fun prepareBiometricCipher(): Cipher? {
        val cipher = vault.prepareDecrypt()
        if (cipher == null) {
            // Enrolling a new fingerprint invalidates the Keystore key. Clear the stale
            // credential instead of leaving a button that can never succeed.
            vault.clear()
            config.biometricEnabled = false
            _state.update {
                it.copy(
                    biometricEnabled = false,
                    notice = "Fingerprint unlock needs to be re-enabled.",
                )
            }
        }
        return cipher
    }

    fun prepareBiometricEnrollmentCipher(): Cipher? {
        val cipher = vault.prepareEncrypt()
        if (cipher == null) {
            // A key invalidated by a new fingerprint enrollment can never encrypt again.
            // Delete it so the next attempt generates a fresh key.
            vault.clear()
        }
        return cipher
    }

    fun completeBiometricEnrollment(cipher: Cipher) {
        val passphrase = Session.passphrase()
        if (passphrase == null) {
            _state.update { it.copy(error = "Unlock first.") }
            return
        }
        val stored = try {
            vault.storeWithCipher(cipher, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        config.biometricEnabled = stored
        _state.update {
            it.copy(
                biometricEnabled = stored,
                error = null,
                notice = if (stored) {
                    "Fingerprint unlock enabled."
                } else {
                    "Fingerprint unlock could not be enabled."
                },
            )
        }
    }

    fun cancelBiometricEnrollment() {
        config.biometricEnabled = false
        _state.update {
            it.copy(
                biometricEnabled = false,
                notice = "Fingerprint unlock was not enabled. You can enable it in Settings.",
            )
        }
    }

    private fun requestBiometricEnrollment() {
        _state.update { it.copy(biometricEnrollRequest = it.biometricEnrollRequest + 1, error = null) }
    }

    fun consumeBiometricEnrollmentRequest() {
        if (_state.value.biometricEnrollRequest != 0L) {
            _state.update { it.copy(biometricEnrollRequest = 0L) }
        }
    }

    fun lock() {
        Session.clear()
        searchJob?.cancel()
        _state.update {
            it.copy(
                locked = true,
                count = 0,
                results = emptyList(),
                detail = null,
                settingsOpen = false,
                syncing = false,
                selectionMode = false,
                selectedIds = emptySet(),
                archivedOpen = false,
                archivedResults = emptyList(),
                composerOpen = false,
                error = null,
                notice = null,
            )
        }
        RecallWidgetProvider.refresh(getApplication())
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value, error = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(140)
            runSearch(value)
        }
    }

    private suspend fun runSearch(value: String) {
        val repository = Session.repository() ?: return
        val current = _state.value
        val results = runCatching {
            if (value.isBlank()) {
                repository.timeline(
                    type = current.typeFilter,
                    domain = current.domain,
                    favoritesOnly = current.favoritesOnly,
                    tag = current.selectedTag,
                    descending = current.sortDescending,
                )
            } else {
                repository.search(value, synonyms)
            }
        }.getOrElse {
            _state.update { state -> state.copy(error = "Search failed.") }
            return
        }
        _state.update { it.copy(results = applyFilters(results, it)) }
    }

    fun setTypeFilter(type: String?) {
        _state.update { it.copy(typeFilter = type) }
        viewModelScope.launch { refreshInternal() }
    }

    fun setDomain(domain: String) {
        _state.update { it.copy(domain = domain) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(220)
            refreshInternal()
        }
    }

    fun setTimelineView(enabled: Boolean) {
        _state.update { it.copy(timelineView = enabled) }
    }

    fun setSortDescending(descending: Boolean) {
        _state.update { it.copy(sortDescending = descending) }
        viewModelScope.launch { refreshInternal() }
    }

    fun setFavoritesOnly(enabled: Boolean) {
        _state.update { it.copy(favoritesOnly = enabled) }
        viewModelScope.launch { refreshInternal() }
    }

    fun setSelectedTag(tag: String?) {
        _state.update { it.copy(selectedTag = tag) }
        viewModelScope.launch { refreshInternal() }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        val repository = Session.repository() ?: return
        val current = _state.value
        val count = runCatching { repository.count() }.getOrDefault(current.count)
        val archivedCount = runCatching { repository.countArchived() }.getOrDefault(current.archivedCount)
        val allTags = runCatching { repository.allTags() }.getOrDefault(current.allTags)
        val selectedTag = current.selectedTag?.takeIf { tag ->
            allTags.any { it.equals(tag, ignoreCase = true) }
        }
        val results = runCatching {
            if (current.query.isNotBlank()) {
                repository.search(current.query, synonyms)
            } else {
                repository.timeline(
                    type = current.typeFilter,
                    domain = current.domain,
                    favoritesOnly = current.favoritesOnly,
                    tag = selectedTag,
                    descending = current.sortDescending,
                )
            }
        }.getOrElse { emptyList() }
        val filtered = applyFilters(results, current.copy(selectedTag = selectedTag))
        val archivedResults = if (current.archivedOpen) {
            runCatching { repository.archived() }.getOrDefault(current.archivedResults)
        } else {
            current.archivedResults
        }
        _state.update {
            it.copy(
                count = count,
                archivedCount = archivedCount,
                allTags = allTags,
                selectedTag = selectedTag,
                results = filtered,
                archivedResults = archivedResults,
            )
        }
    }

    private fun applyFilters(results: List<SearchResult>, state: RecallUiState): List<SearchResult> {
        val domain = state.domain.trim().lowercase(Locale.ROOT)
        val filtered = results.filter { result ->
            (state.typeFilter == null || result.type.wire == state.typeFilter) &&
                (domain.isEmpty() || result.url?.lowercase(Locale.ROOT)?.contains(domain) == true) &&
                (!state.favoritesOnly || result.favorite) &&
                (state.selectedTag == null || result.tags.any {
                    it.equals(state.selectedTag, ignoreCase = true)
                })
        }
        return if (state.sortDescending) {
            filtered.sortedWith(
                compareByDescending<SearchResult> { it.createdAt }.thenBy { it.id },
            )
        } else {
            filtered.sortedWith(compareBy<SearchResult> { it.createdAt }.thenBy { it.id })
        }
    }

    fun openComposer() {
        _state.update {
            it.copy(
                composerOpen = true,
                selectionMode = false,
                selectedIds = emptySet(),
                error = null,
                notice = null,
            )
        }
    }

    fun closeComposer() {
        _state.update { it.copy(composerOpen = false) }
    }

    fun saveQuickCapture(text: String, tags: String, favorite: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(error = "Write something first.") }
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            val saved = CaptureSaver.save(
                context = context,
                payload = payloadForText(clean, null),
                favorite = favorite,
                tags = parseTags(tags),
            )
            if (saved) {
                _state.update { it.copy(composerOpen = false, notice = "Saved to Recall.") }
                refreshInternal()
            } else {
                _state.update { it.copy(error = "Could not save that capture.") }
            }
        }
    }

    fun startSelection(id: String? = null) {
        _state.update {
            it.copy(
                selectionMode = true,
                selectedIds = if (id == null) emptySet() else setOf(id),
                composerOpen = false,
                error = null,
            )
        }
    }

    fun toggleSelection(id: String) {
        _state.update {
            val selected = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = selected)
        }
    }

    fun cancelSelection() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun archiveSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) {
            _state.update { it.copy(notice = "Select at least one capture.") }
            return
        }
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            val count = repository.archiveMany(ids)
            _state.update {
                it.copy(
                    selectionMode = false,
                    selectedIds = emptySet(),
                    detail = null,
                    notice = if (count > 0) "Archived $count capture(s)." else "Nothing archived.",
                )
            }
            RecallWidgetProvider.refresh(getApplication())
            refreshInternal()
        }
    }

    fun archiveCapture(id: String) {
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            repository.setArchived(id, true)
            _state.update {
                it.copy(
                    detail = null,
                    selectedIds = it.selectedIds - id,
                    notice = "Moved to archive.",
                )
            }
            RecallWidgetProvider.refresh(getApplication())
            refreshInternal()
        }
    }

    fun restoreCapture(id: String) {
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            repository.setArchived(id, false)
            _state.update {
                it.copy(
                    detail = null,
                    notice = "Restored from archive.",
                )
            }
            RecallWidgetProvider.refresh(getApplication())
            refreshInternal()
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            val current = _state.value
            val isFavorite = current.detail?.takeIf { it.id == id }?.favorite
                ?: current.results.firstOrNull { it.id == id }?.favorite
                ?: current.archivedResults.firstOrNull { it.id == id }?.favorite
                ?: false
            repository.setFavorite(id, !isFavorite)
            val updatedDetail = current.detail?.takeIf { it.id == id }?.let { repository.detail(id) }
            _state.update { it.copy(detail = updatedDetail ?: it.detail) }
            refreshInternal()
        }
    }

    fun addTag(id: String, value: String) {
        if (value.isBlank()) return
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            repository.addTag(id, value)
            val current = _state.value
            if (current.detail?.id == id) {
                _state.update { it.copy(detail = repository.detail(id)) }
            }
            refreshInternal()
        }
    }

    fun removeTag(id: String, value: String) {
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            repository.removeTag(id, value)
            val current = _state.value
            if (current.detail?.id == id) {
                _state.update { it.copy(detail = repository.detail(id)) }
            }
            refreshInternal()
        }
    }

    fun openArchived() {
        _state.update {
            it.copy(
                archivedOpen = true,
                settingsOpen = false,
                detail = null,
                selectionMode = false,
                selectedIds = emptySet(),
                composerOpen = false,
                error = null,
            )
        }
        viewModelScope.launch { loadArchived() }
    }

    fun closeArchived() {
        _state.update { it.copy(archivedOpen = false, detail = null) }
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun loadArchived() {
        val repository = Session.repository() ?: return
        val results = runCatching { repository.archived() }.getOrDefault(emptyList())
        val count = runCatching { repository.countArchived() }.getOrDefault(results.size)
        _state.update { it.copy(archivedResults = results, archivedCount = count) }
    }

    private fun parseTags(value: String): List<String> =
        value.split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)

    fun openDetail(id: String) {
        viewModelScope.launch {
            val detail = Session.repository()?.detail(id) ?: return@launch
            _state.update { it.copy(detail = detail) }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    fun saveClipboard() {
        if (!Session.isUnlocked()) return
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
        val uri = item.uri
        val label = clipboard.primaryClipDescription?.label?.toString()?.trim()?.ifBlank { null }
        val sourceUrl = uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        viewModelScope.launch {
            val saved = when {
                text.isNotEmpty() -> CaptureSaver.save(context, payloadForText(text, label, sourceUrl))
                uri != null -> CaptureSaver.saveScreenshot(context, uri)
                else -> false
            }
            afterSave(saved, "Clipboard saved to Recall.", "Nothing to save.")
        }
    }

    /** Saves a share/selection payload handed over while the vault was locked. */
    fun savePendingShare(payload: PendingShare.Payload) {
        if (!Session.isUnlocked()) {
            PendingShare.set(payload)
            return
        }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = when {
                payload.uri != null && payload.type.startsWith("image/") ->
                    CaptureSaver.saveScreenshot(context, payload.uri)
                !payload.text.isNullOrBlank() ->
                    CaptureSaver.save(context, payloadForText(payload.text, payload.subject))
                else -> false
            }
            afterSave(saved, "Saved to Recall.", "Could not save that capture.")
        }
    }

    private suspend fun afterSave(saved: Boolean, success: String, failure: String) {
        _state.update { it.copy(notice = if (saved) success else failure) }
        if (saved) {
            RecallWidgetProvider.refresh(getApplication())
            refreshInternal()
        }
    }

    fun sync() {
        val repository = Session.repository() ?: return
        val stored = config.load()
        if (stored.localOnly) {
            _state.update { it.copy(notice = "Local-only mode: no relay configured.") }
            return
        }
        val rawKey = Session.rawKey() ?: return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null, notice = null) }
            try {
                val report = SyncEngine(config, repository, rawKey).sync()
                _state.update {
                    it.copy(
                        syncing = false,
                        lastSyncAt = config.lastSyncAt,
                        notice = "Synced: ${report.pulled} in, ${report.pushed} out" +
                            if (report.failed > 0) " (${report.failed} failed)" else "",
                    )
                }
                refreshInternal()
            } catch (error: Exception) {
                _state.update { it.copy(syncing = false, error = error.message ?: "sync-failed") }
            } finally {
                rawKey.fill(0)
            }
        }
    }

    fun openSettings() {
        _state.update {
            it.copy(
                settingsOpen = true,
                archivedOpen = false,
                composerOpen = false,
                detail = null,
                selectionMode = false,
                selectedIds = emptySet(),
                pinConfigured = pinVault.hasPin,
                pendingScreenshots = config.pendingScreenshots().size,
            )
        }
    }

    fun closeSettings() {
        _state.update { it.copy(settingsOpen = false) }
    }

    fun setScreenshotMode(mode: String) {
        config.screenshotMode = mode
        if (mode != AppConfig.MODE_OFF && config.lastScreenshotSeen == 0L) {
            config.lastScreenshotSeen = System.currentTimeMillis()
        }
        if (mode != AppConfig.MODE_OFF) {
            ScreenshotScanWorker.schedule(getApplication())
        }
        _state.update {
            it.copy(
                screenshotMode = mode,
                notice = when (mode) {
                    AppConfig.MODE_OFF -> "Screenshot proposals are off."
                    AppConfig.MODE_ASK -> "Recall will ask before saving new screenshots."
                    AppConfig.MODE_HOLD -> "Screenshots are held for 60 minutes before being discarded."
                    else -> "Screenshots from allowlisted apps are saved automatically."
                },
            )
        }
    }

    fun addAllowlist(packageName: String) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return
        val updated = config.allowlist + cleaned
        config.allowlist = updated
        _state.update { it.copy(allowlist = updated) }
    }

    fun removeAllowlist(packageName: String) {
        val updated = config.allowlist - packageName
        config.allowlist = updated
        _state.update { it.copy(allowlist = updated) }
    }

    fun enableBiometric() {
        if (!_state.value.biometricAvailable) {
            _state.update { it.copy(error = "No strong biometric is enrolled on this device.") }
            return
        }
        val passphrase = Session.passphrase()
        val unlocked = passphrase != null
        passphrase?.fill('\u0000')
        if (!unlocked) {
            _state.update { it.copy(error = "Unlock first.") }
            return
        }
        requestBiometricEnrollment()
    }

    fun disableBiometric() {
        vault.clear()
        config.biometricEnabled = false
        _state.update { it.copy(biometricEnabled = false, notice = "Biometric unlock disabled.") }
    }

    fun setPin(pinText: String) {
        if (pinText.length != PinVault.PIN_LENGTH || pinText.any { !it.isDigit() }) {
            _state.update { it.copy(error = "Choose a 4-digit PIN.") }
            return
        }
        val passphrase = Session.passphrase()
        if (passphrase == null) {
            _state.update { it.copy(error = "Unlock first.") }
            return
        }
        val pin = pinText.toCharArray()
        viewModelScope.launch {
            val stored = withContext(Dispatchers.Default) {
                try {
                    pinVault.setPin(pin, passphrase)
                } finally {
                    passphrase.fill('\u0000')
                    pin.fill('\u0000')
                }
            }
            _state.update {
                it.copy(
                    pinConfigured = pinVault.hasPin,
                    notice = if (stored) {
                        "4-digit PIN set. You can now unlock with your PIN."
                    } else {
                        "Could not store the PIN on this device."
                    },
                )
            }
        }
    }

    fun savePendingScreenshots() {
        if (!Session.isUnlocked()) return
        val context = getApplication<Application>()
        val pending = config.pendingScreenshots()
        viewModelScope.launch {
            var saved = 0
            for (item in pending) {
                if (CaptureSaver.saveScreenshot(context, Uri.parse(item.uri))) {
                    config.removePending(item.uri)
                    ScreenshotNotifier.cancel(context, Uri.parse(item.uri))
                    saved += 1
                }
            }
            _state.update { it.copy(pendingScreenshots = config.pendingScreenshots().size) }
            afterSave(saved > 0, "$saved screenshot(s) saved.", "Could not save the held screenshots.")
        }
    }

    fun discardPendingScreenshots() {
        val context = getApplication<Application>()
        for (item in config.pendingScreenshots()) {
            ScreenshotNotifier.cancel(context, Uri.parse(item.uri))
        }
        config.clearPending()
        _state.update { it.copy(pendingScreenshots = 0, notice = "Held screenshots discarded.") }
    }

    fun exportTo(uri: Uri) {
        val repository = Session.repository() ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { repository.exportTo(app.contentResolver, uri) }
                _state.update { it.copy(notice = "Exported $count encrypted capture(s).") }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "export-failed") }
            }
        }
    }

    fun importFrom(uri: Uri) {
        val repository = Session.repository() ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { repository.importFrom(app.contentResolver, uri) }
                _state.update { it.copy(notice = "Imported $count encrypted capture(s).") }
                RecallWidgetProvider.refresh(app)
                refreshInternal()
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "import-failed") }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    private fun loadSynonyms(context: Context): SynonymIndex = runCatching {
        context.assets.open("synonyms.json").bufferedReader().use { it.readText() }
            .let { buildSynonymIndex(parseSynonymDictionary(it)) }
    }.getOrDefault(SynonymIndex.EMPTY)
}
