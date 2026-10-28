package com.recall.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recall.app.Session
import com.recall.app.data.CaptureDetail
import com.recall.app.sync.SyncWorker
import com.recall.core.CapturePayload
import com.recall.core.CaptureType
import com.recall.app.widget.RecallWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class CaptureEditorState(
    val loading: Boolean = true,
    val locked: Boolean = false,
    val missing: Boolean = false,
    val isNew: Boolean = false,
    val detail: CaptureDetail? = null,
    val title: String = "",
    val text: String = "",
    val url: String = "",
    val ocrText: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class CaptureEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(CaptureEditorState())
    val state: StateFlow<CaptureEditorState> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(captureId: String) {
        if (captureId.isBlank()) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        if (loadedId == captureId && _state.value.detail != null) return
        loadedId = captureId
        if (!Session.isUnlocked()) {
            _state.update { it.copy(loading = false, locked = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val detail = runCatching { Session.repository()?.detail(captureId) }.getOrNull()
            if (detail == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            _state.value = CaptureEditorState(
                loading = false,
                detail = detail,
                title = detail.rawTitle.orEmpty(),
                text = detail.text.orEmpty(),
                url = detail.url.orEmpty(),
                ocrText = detail.ocrText.orEmpty(),
                tags = detail.tags,
                favorite = detail.favorite,
                archived = detail.archived,
            )
        }
    }

    fun startNew() {
        loadedId = null
        _state.value = if (Session.isUnlocked()) {
            CaptureEditorState(loading = false, isNew = true)
        } else {
            CaptureEditorState(loading = false, isNew = true, locked = true)
        }
    }

    fun setTitle(value: String) {
        _state.update { it.copy(title = value, error = null) }
    }

    fun setText(value: String) {
        _state.update { it.copy(text = value, error = null) }
    }

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun setOcrText(value: String) {
        _state.update { it.copy(ocrText = value, error = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    fun addTag(value: String) {
        val tag = normalizeTag(value) ?: return
        _state.update { current ->
            when {
                current.tags.size >= MAX_TAGS ->
                    current.copy(error = "A capture can have up to $MAX_TAGS tags.")
                current.tags.any { it.equals(tag, ignoreCase = true) } -> current
                else -> current.copy(tags = current.tags + tag, error = null)
            }
        }
    }

    fun removeTag(value: String) {
        _state.update { current ->
            current.copy(tags = current.tags.filterNot { it.equals(value, ignoreCase = true) })
        }
    }

    fun toggleFavorite() {
        val current = _state.value
        if (current.isNew) return
        val detail = current.detail ?: return
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            val favorite = !current.favorite
            repository.setFavorite(detail.id, favorite)
            _state.update { it.copy(favorite = favorite) }
        }
    }

    fun setArchived(archived: Boolean) {
        val current = _state.value
        if (current.isNew) return
        val detail = current.detail ?: return
        viewModelScope.launch {
            val repository = Session.repository() ?: return@launch
            repository.setArchived(detail.id, archived)
            RecallWidgetProvider.refresh(getApplication())
            if (archived) {
                _state.update { it.copy(archived = true, finished = true) }
            } else {
                _state.update { it.copy(archived = false, notice = "Restored from archive.") }
            }
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (!current.isNew && current.detail == null) return

        val title = current.title.trim()
        val text = current.text.trimEnd()
        val url = current.url.trim()
        val ocrText = current.ocrText.trimEnd()
        if (title.isEmpty() && text.isEmpty() && url.isEmpty() && ocrText.isEmpty()) {
            _state.update { it.copy(error = "Keep a title, some text, or a link.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val repository = Session.repository()
            if (repository == null) {
                _state.update { it.copy(saving = false, locked = true) }
                return@launch
            }
            val saved = runCatching {
                if (current.isNew) {
                    val payload = buildPayload(title, text, url, ocrText) ?: return@runCatching false
                    repository.insert(payload = payload, tags = current.tags)
                } else {
                    val id = current.detail?.id ?: return@runCatching false
                    repository.update(
                        id = id,
                        title = title,
                        text = text,
                        url = url,
                        ocrText = ocrText,
                        tags = current.tags,
                    ) != null
                }
            }.getOrDefault(false)
            if (!saved) {
                _state.update { it.copy(saving = false, error = "Could not save this capture.") }
                return@launch
            }
            RecallWidgetProvider.refresh(getApplication())
            SyncWorker.runOnce(getApplication())
            _state.update { it.copy(saving = false, finished = true) }
        }
    }

    private fun buildPayload(
        title: String,
        text: String,
        url: String,
        ocrText: String,
    ): CapturePayload? {
        val type = when {
            url.isNotEmpty() -> CaptureType.URL
            text.isNotEmpty() -> CaptureType.TEXT
            ocrText.isNotEmpty() -> CaptureType.SCREENSHOT
            title.isNotEmpty() -> CaptureType.TEXT
            else -> return null
        }
        val body = when {
            text.isNotEmpty() -> text
            type == CaptureType.TEXT && title.isNotEmpty() -> title
            else -> null
        }
        return CapturePayload(
            type = type.wire,
            url = url.takeIf { it.isNotEmpty() },
            title = title.takeIf { it.isNotEmpty() },
            text = body,
            ocrText = ocrText.takeIf { it.isNotEmpty() },
        )
    }

    private fun normalizeTag(value: String): String? {
        val tag = value.trim().lowercase(Locale.ROOT).take(MAX_TAG_LENGTH)
        return tag.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_TAGS = 8
        const val MAX_TAG_LENGTH = 32
    }
}
