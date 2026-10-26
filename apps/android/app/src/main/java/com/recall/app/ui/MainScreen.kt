package com.recall.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.recall.app.data.CaptureDetail
import com.recall.core.CaptureType
import com.recall.core.Normalize
import com.recall.core.SearchResult
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: RecallUiState, viewModel: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MainHeader(state = state, viewModel = viewModel)
        Spacer(Modifier.height(14.dp))
        RecallTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = null,
            icon = Icons.Outlined.Search,
            placeholder = "Search your memory…",
            modifier = Modifier.padding(horizontal = 20.dp),
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Clear search",
                        tint = RecallFaint,
                        modifier = Modifier
                            .clickable { viewModel.setQuery("") }
                            .padding(6.dp)
                            .size(18.dp),
                    )
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(10.dp))
        ModeTabs(
            mode = state.mode,
            onSelect = viewModel::setMode,
        )

        if (state.mode == RecallUiState.MODE_TIMELINE) {
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    null to "All",
                    "url" to "Links",
                    "text" to "Text",
                    "screenshot" to "Screenshots",
                ).forEach { (value, label) ->
                    FilterPill(
                        selected = state.typeFilter == value,
                        label = label,
                        onClick = { viewModel.setTypeFilter(value) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            RecallTextField(
                value = state.domain,
                onValueChange = viewModel::setDomain,
                label = null,
                icon = Icons.Outlined.Language,
                placeholder = "Filter by domain (optional)",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (state.pendingScreenshots > 0) {
            Spacer(Modifier.height(10.dp))
            PendingScreenshotsCard(
                count = state.pendingScreenshots,
                onSave = viewModel::savePendingScreenshots,
                onDiscard = viewModel::discardPendingScreenshots,
            )
        }

        MessageCard(state.error, state.notice, viewModel::clearMessages)

        val terms = remember(state.query) { Normalize.normalizeQuery(state.query).terms }
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::sync,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (state.results.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.results, key = { it.id }) { result ->
                        ResultCard(
                            result = result,
                            terms = terms,
                            onClick = { viewModel.openDetail(result.id) },
                        )
                    }
                }
            }
        }

        MainBottomBar(state = state, viewModel = viewModel)
    }
}

@Composable
private fun MainHeader(state: RecallUiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecallMark(size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Recall", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.count} ${if (state.count == 1) "capture" else "captures"} · encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
        }
        RecallIconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            onClick = viewModel::openSettings,
        )
        RecallIconButton(
            icon = Icons.Outlined.Lock,
            contentDescription = "Lock Recall",
            onClick = viewModel::lock,
        )
    }
}

@Composable
private fun ModeTabs(
    mode: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
            .padding(3.dp),
    ) {
        ModeTab(
            selected = mode == RecallUiState.MODE_SEARCH,
            label = "Search",
            icon = Icons.Outlined.Search,
            onClick = { onSelect(RecallUiState.MODE_SEARCH) },
            modifier = Modifier.weight(1f),
        )
        ModeTab(
            selected = mode == RecallUiState.MODE_TIMELINE,
            label = "Timeline",
            icon = Icons.Outlined.Timeline,
            onClick = { onSelect(RecallUiState.MODE_TIMELINE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeTab(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) RecallViolet.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) RecallViolet.copy(alpha = 0.26f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) RecallCyan else RecallMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) RecallText else RecallMuted,
        )
    }
}

@Composable
private fun FilterPill(
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
private fun PendingScreenshotsCard(
    count: Int,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp),
        padding = 14.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            IconBadge(icon = Icons.Outlined.Photo, tint = RecallPink, size = 38.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$count screenshot${if (count == 1) "" else "s"} held",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Review within 60 minutes",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onSave) {
                Icon(Icons.Outlined.Save, null, tint = RecallCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Save all", color = RecallCyan, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onDiscard) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = RecallMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Discard", color = RecallMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = Icons.Outlined.AutoAwesome, tint = RecallPink, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("Nothing here yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Share text or images to Recall, use the selection menu, or capture the clipboard. " +
                "Your first memory will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = RecallMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultCard(result: SearchResult, terms: List<String>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        RecallInk700.copy(alpha = 0.86f),
                        RecallInk900.copy(alpha = 0.82f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        result.thumbnailB64?.let { encoded ->
            Thumbnail(
                encoded = encoded,
                modifier = Modifier.fillMaxWidth().height(98.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = result.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (result.snippet.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            HighlightedSnippet(result.snippet, terms)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = typeIcon(result.type),
                contentDescription = null,
                tint = RecallFaint,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = relativeTime(result.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
            )
            Text("·", style = MaterialTheme.typography.labelSmall, color = RecallFaint)
            Text(
                text = typeLabel(result.type),
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
            )
        }
    }
}

@Composable
private fun Thumbnail(encoded: String, modifier: Modifier = Modifier) {
    val bitmap = remember(encoded) {
        runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun HighlightedSnippet(text: String, terms: List<String>) {
    val style = MaterialTheme.typography.bodySmall
    val color = RecallMuted
    if (terms.isEmpty()) {
        Text(text, style = style, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
        return
    }
    val lower = text.lowercase(Locale.ROOT)
    val ranges = ArrayList<IntRange>()
    for (term in terms) {
        if (term.isBlank()) continue
        var index = lower.indexOf(term.lowercase(Locale.ROOT))
        while (index >= 0) {
            ranges += index until (index + term.length)
            index = lower.indexOf(term.lowercase(Locale.ROOT), index + term.length)
        }
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        append(text)
        for (range in ranges) {
            if (range.first < text.length && range.last < text.length) {
                addStyle(
                    SpanStyle(color = RecallPink, fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
    Text(annotated, style = style, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MainBottomBar(state: RecallUiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when {
                    state.syncing -> "Syncing…"
                    state.localOnly -> "Local-only vault"
                    state.lastSyncAt > 0 -> "Synced ${relativeTime(state.lastSyncAt)}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.labelSmall,
                color = RecallMuted,
            )
            Text(
                "AES-256-GCM · encrypted at rest",
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
            )
        }
        CaptureButton(onClick = viewModel::saveClipboard)
        if (state.syncing) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = RecallCyan,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            RecallIconButton(
                icon = Icons.Outlined.Sync,
                contentDescription = "Sync now",
                onClick = viewModel::sync,
                enabled = !state.localOnly,
            )
        }
    }
}

@Composable
private fun CaptureButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .height(42.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(RecallViolet, RecallCyan)))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = RecallInk950,
            modifier = Modifier.size(17.dp),
        )
        Text(
            "Capture",
            style = MaterialTheme.typography.labelMedium,
            color = RecallInk950,
        )
    }
}

@Composable
fun DetailDialog(detail: CaptureDetail, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(RecallInk700, RecallInk900),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(icon = typeIcon(detail.type), tint = RecallCyan, size = 42.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SectionEyebrow(icon = typeIcon(detail.type), text = typeLabel(detail.type))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RecallIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Close capture",
                    onClick = onDismiss,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${relativeTime(detail.createdAt)} · ${if (detail.synced) "Synced" else "Local only"}",
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
            )

            detail.thumbnail?.let { bytes ->
                val bitmap = remember(bytes) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                }
                if (bitmap != null) {
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            detail.text?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = RecallText)
            }
            detail.ocrText?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Recognized text",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallCyan,
                )
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = RecallMuted)
            }
            detail.url?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallCyan,
                )
            }

            Spacer(Modifier.height(18.dp))
            if (detail.url != null) {
                RecallPrimaryButton(
                    text = "Open link",
                    icon = Icons.Outlined.OpenInNew,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.url)))
                        }
                    },
                    height = 48.dp,
                )
            } else {
                RecallPrimaryButton(
                    text = "Close",
                    icon = Icons.Outlined.Close,
                    onClick = onDismiss,
                    height = 48.dp,
                )
            }
        }
    }
}

private fun typeIcon(type: CaptureType) = when (type) {
    CaptureType.URL -> Icons.Outlined.Link
    CaptureType.TEXT -> Icons.Outlined.Article
    CaptureType.SCREENSHOT -> Icons.Outlined.Photo
}

fun typeLabel(type: CaptureType): String = when (type) {
    CaptureType.URL -> "link"
    CaptureType.TEXT -> "text"
    CaptureType.SCREENSHOT -> "screenshot"
}

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - timestamp).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        minutes < 10_080 -> "${minutes / 1_440}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
