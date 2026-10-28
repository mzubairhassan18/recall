package com.recall.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun CaptureEditorScreen(
    state: CaptureEditorState,
    viewModel: CaptureEditorViewModel,
    onClose: () -> Unit,
) {
    AuroraBackground {
        when {
            state.loading -> EditorLoading()
            state.locked -> EditorMessage(
                icon = Icons.Outlined.Lock,
                title = "Recall is locked",
                body = "Unlock Recall from the main window, then open this capture again.",
                onClose = onClose,
            )
            state.missing || (!state.isNew && state.detail == null) -> EditorMessage(
                icon = Icons.Outlined.Info,
                title = "Capture not found",
                body = "It may have been removed on another device.",
                onClose = onClose,
            )
            else -> EditorContent(state = state, viewModel = viewModel, onClose = onClose)
        }
    }
}

@Composable
private fun EditorLoading() {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = RecallCyan, strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text("Opening capture…", style = MaterialTheme.typography.bodyMedium, color = RecallMuted)
    }
}

@Composable
private fun EditorMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = icon, tint = RecallCyan, size = 62.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = RecallMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        RecallPrimaryButton(text = "Back to Recall", icon = Icons.Outlined.ArrowBack, onClick = onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    state: CaptureEditorState,
    viewModel: CaptureEditorViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val detail = state.detail
    var newTag by rememberSaveable(detail?.id ?: "new") { mutableStateOf("") }
    val bitmap = remember(detail?.thumbnail) {
        detail?.thumbnail?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecallIconButton(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "Close editor",
                onClick = onClose,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.isNew) "New capture" else "Edit capture",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RecallText,
                )
                Text(
                    if (state.isNew) {
                        "Encrypted · queued for sync"
                    } else {
                        detail?.let {
                            "${typeLabel(it.type)} · ${relativeTime(it.createdAt)} · ${
                                if (it.archived) "archived" else "active"
                            }"
                        }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!state.isNew) {
                RecallIconButton(
                    icon = if (state.favorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (state.favorite) "Remove favorite" else "Add favorite",
                    onClick = viewModel::toggleFavorite,
                    tint = if (state.favorite) RecallPink else RecallMuted,
                )
            }
        }

        if (!state.isNew) {
            Spacer(Modifier.height(10.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    detail?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = RecallText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Encrypted · queued for sync",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallFaint,
                )
                if (bitmap != null) {
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        GlassCard(padding = 12.dp) {
            RecallTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = "Title",
                placeholder = "Optional title",
            )
            Spacer(Modifier.height(10.dp))
            RecallTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = "Text",
                placeholder = "Write or paste the content",
                singleLine = false,
                minLines = 7,
                maxLines = 18,
            )
            Spacer(Modifier.height(10.dp))
            RecallTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = "Link",
                icon = Icons.Outlined.Link,
                placeholder = "https://…",
                keyboardType = KeyboardType.Uri,
            )
            if (!state.isNew && (state.ocrText.isNotBlank() || detail?.ocrText?.isNotBlank() == true)) {
                Spacer(Modifier.height(10.dp))
                RecallTextField(
                    value = state.ocrText,
                    onValueChange = viewModel::setOcrText,
                    label = "Recognized text",
                    placeholder = "Text found in the screenshot",
                    singleLine = false,
                    minLines = 4,
                    maxLines = 12,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tags", style = MaterialTheme.typography.titleSmall, color = RecallText)
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.tags.size}/8",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallFaint,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.tags.isEmpty()) {
                Text(
                    "No tags yet. Add one below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.tags.forEach { tag ->
                        EditorTagChip(text = tag, onRemove = { viewModel.removeTag(tag) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecallTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = null,
                    icon = Icons.Outlined.Tag,
                    placeholder = "Add a tag",
                    modifier = Modifier.weight(1f),
                )
                RecallIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "Add tag",
                    onClick = {
                        if (newTag.isNotBlank()) {
                            viewModel.addTag(newTag)
                            newTag = ""
                        }
                    },
                    tint = RecallCyan,
                )
            }
        }

        MessageCard(state.error, state.notice, viewModel::clearMessages)

        Spacer(Modifier.height(14.dp))
        RecallPrimaryButton(
            text = if (state.isNew) "Save capture" else "Save changes",
            icon = Icons.Outlined.Save,
            onClick = viewModel::save,
            loading = state.saving,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isNew) {
                TextButton(onClick = onClose) {
                    Text("Cancel", color = RecallMuted, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                TextButton(onClick = { viewModel.setArchived(!state.archived) }) {
                    Icon(
                        imageVector = if (state.archived) Icons.Outlined.Restore else Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = if (state.archived) RecallCyan else RecallMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (state.archived) "Restore" else "Archive",
                        color = if (state.archived) RecallCyan else RecallMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.url.isNotBlank()) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.url)))
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = RecallCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Open link", color = RecallCyan, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EditorTagChip(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(RecallViolet.copy(alpha = 0.12f))
            .border(1.dp, RecallViolet.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("#$text", style = MaterialTheme.typography.labelSmall, color = RecallTagText)
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove tag $text",
            tint = RecallFaint,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .size(14.dp)
                .padding(1.dp),
        )
    }
}
