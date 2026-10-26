package com.recall.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.util.Calendar
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
        FilterRow(state = state, viewModel = viewModel)
        if (state.allTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TagFilterRow(state = state, viewModel = viewModel)
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

        if (state.selectionMode) {
            SelectionBar(state = state, viewModel = viewModel)
        }

        val terms = remember(state.query) { Normalize.normalizeQuery(state.query).terms }
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::sync,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (state.results.isEmpty()) {
                EmptyState(state = state, modifier = Modifier.fillMaxSize())
            } else if (state.timelineView) {
                TimelineResults(
                    results = state.results,
                    terms = terms,
                    state = state,
                    viewModel = viewModel,
                )
            } else {
                GridResults(
                    results = state.results,
                    terms = terms,
                    state = state,
                    viewModel = viewModel,
                )
            }
        }

        MainBottomBar(state = state, viewModel = viewModel)
    }

    if (state.composerOpen) {
        QuickCaptureDialog(state = state, viewModel = viewModel)
    }
}

@Composable
private fun MainHeader(state: RecallUiState, viewModel: AppViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecallMark(size = 46.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("Recall", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.count} ${if (state.count == 1) "capture" else "captures"} · encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RecallIconButton(
            icon = Icons.Outlined.Timeline,
            contentDescription = if (state.timelineView) "Show grid" else "Show timeline",
            onClick = { viewModel.setTimelineView(!state.timelineView) },
            tint = if (state.timelineView) RecallCyan else RecallMuted,
        )
        Box {
            RecallIconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More options",
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Select captures") },
                    leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.startSelection()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (state.archivedCount > 0) {
                                "Archived (${state.archivedCount})"
                            } else {
                                "Archived"
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.openArchived()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.openSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Lock now") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.lock()
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterRow(state: RecallUiState, viewModel: AppViewModel) {
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
        FilterPill(
            selected = state.favoritesOnly,
            label = "Favorites",
            icon = Icons.Outlined.Star,
            onClick = { viewModel.setFavoritesOnly(!state.favoritesOnly) },
        )
        FilterPill(
            selected = false,
            label = if (state.sortDescending) "Newest" else "Oldest",
            icon = Icons.Outlined.Sort,
            onClick = { viewModel.setSortDescending(!state.sortDescending) },
        )
    }
}

@Composable
private fun TagFilterRow(state: RecallUiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Tag,
            contentDescription = null,
            tint = RecallFaint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Tags",
            style = MaterialTheme.typography.labelSmall,
            color = RecallFaint,
            modifier = Modifier.padding(end = 2.dp),
        )
        state.allTags.forEach { tag ->
            FilterPill(
                selected = state.selectedTag == tag,
                label = "#$tag",
                onClick = {
                    viewModel.setSelectedTag(if (state.selectedTag == tag) null else tag)
                },
            )
        }
        if (state.selectedTag != null) {
            FilterPill(
                selected = false,
                label = "Clear",
                onClick = { viewModel.setSelectedTag(null) },
            )
        }
    }
}

@Composable
private fun FilterPill(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Row(
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (selected) RecallCyan else RecallMuted,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) RecallText else RecallMuted,
        )
    }
}

@Composable
private fun SelectionBar(state: RecallUiState, viewModel: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RecallViolet.copy(alpha = 0.10f))
            .border(1.dp, RecallViolet.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${state.selectedIds.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = RecallText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = viewModel::archiveSelected, enabled = state.selectedIds.isNotEmpty()) {
            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Archive", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = viewModel::cancelSelection) {
            Text("Cancel", style = MaterialTheme.typography.labelMedium, color = RecallMuted)
        }
    }
}

@Composable
private fun GridResults(
    results: List<SearchResult>,
    terms: List<String>,
    state: RecallUiState,
    viewModel: AppViewModel,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(results, key = { it.id }) { result ->
            ResultCard(
                result = result,
                terms = terms,
                selectionMode = state.selectionMode,
                selected = result.id in state.selectedIds,
                onClick = {
                    if (state.selectionMode) viewModel.toggleSelection(result.id) else viewModel.openDetail(result.id)
                },
                onLongClick = { viewModel.startSelection(result.id) },
                onToggleFavorite = { viewModel.toggleFavorite(result.id) },
            )
        }
    }
}

@Composable
private fun TimelineResults(
    results: List<SearchResult>,
    terms: List<String>,
    state: RecallUiState,
    viewModel: AppViewModel,
) {
    val groups = results.groupBy { dayKey(it.createdAt) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (key, items) ->
            item(key = "header-$key") {
                DateHeader(label = dateLabel(items.first().createdAt))
            }
            items(items, key = { it.id }) { result ->
                ResultCard(
                    result = result,
                    terms = terms,
                    selectionMode = state.selectionMode,
                    selected = result.id in state.selectedIds,
                    onClick = {
                        if (state.selectionMode) {
                            viewModel.toggleSelection(result.id)
                        } else {
                            viewModel.openDetail(result.id)
                        }
                    },
                    onLongClick = { viewModel.startSelection(result.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(result.id) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RecallMuted)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.07f)),
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
private fun EmptyState(state: RecallUiState, modifier: Modifier = Modifier) {
    val filtered = state.query.isNotBlank() ||
        state.typeFilter != null ||
        state.domain.isNotBlank() ||
        state.favoritesOnly ||
        state.selectedTag != null
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = Icons.Outlined.AutoAwesome, tint = RecallPink, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(if (filtered) "No matches" else "Nothing here yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (filtered) {
                "Try a different word, tag, or filter."
            } else {
                "Tap New to type a thought, share text or images to Recall, or capture the clipboard. " +
                    "Your first memory will appear here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = RecallMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(
    result: SearchResult,
    terms: List<String>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            RecallViolet.copy(alpha = 0.24f),
                            RecallInk900.copy(alpha = 0.92f),
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            RecallInk700.copy(alpha = 0.86f),
                            RecallInk900.copy(alpha = 0.82f),
                        ),
                    )
                },
            )
            .border(
                1.dp,
                if (selected) RecallViolet.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(11.dp),
    ) {
        result.thumbnailB64?.let { encoded ->
            Thumbnail(
                encoded = encoded,
                modifier = Modifier.fillMaxWidth().height(98.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selectionMode) {
                SelectionMark(selected)
            } else if (result.favorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = RecallPink,
                    modifier = Modifier
                        .clickable(onClick = onToggleFavorite)
                        .padding(4.dp)
                        .size(17.dp),
                )
            }
        }
        if (result.snippet.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            HighlightedSnippet(result.snippet, terms)
        }
        if (result.tags.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                result.tags.take(2).forEach { tag ->
                    TagChip(text = tag)
                }
                if (result.tags.size > 2) {
                    Text(
                        text = "+${result.tags.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = RecallFaint,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
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
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) RecallViolet else Color.White.copy(alpha = 0.08f))
            .border(
                1.dp,
                if (selected) RecallViolet else Color.White.copy(alpha = 0.22f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TagChip(
    text: String,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(RecallViolet.copy(alpha = 0.12f))
            .border(1.dp, RecallViolet.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "#$text",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD8D2FF),
            maxLines = 1,
        )
        onRemove?.let {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove tag",
                tint = RecallFaint,
                modifier = Modifier
                    .clickable(onClick = it)
                    .size(12.dp),
            )
        }
    }
}

@Composable
private fun Thumbnail(encoded: String, modifier: Modifier = Modifier) {
    val bitmap = remember(encoded) {
        runCatching {
            val bytes = java.util.Base64.getDecoder().decode(encoded)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "AES-256-GCM · encrypted at rest",
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NewCaptureButton(onClick = viewModel::openComposer)
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
private fun NewCaptureButton(onClick: () -> Unit) {
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
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = RecallInk950,
            modifier = Modifier.size(17.dp),
        )
        Text(
            "New",
            style = MaterialTheme.typography.labelMedium,
            color = RecallInk950,
        )
    }
}

@Composable
private fun QuickCaptureDialog(state: RecallUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var favorite by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = viewModel::closeComposer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(colors = listOf(RecallInk700, RecallInk900)))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Outlined.Add, tint = RecallCyan, size = 42.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("New capture", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Type it now. Encrypt it on save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RecallMuted,
                    )
                }
                RecallIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Close composer",
                    onClick = viewModel::closeComposer,
                )
            }
            Spacer(Modifier.height(14.dp))
            RecallTextField(
                value = text,
                onValueChange = { text = it },
                label = null,
                placeholder = "Type a thought, paste a link, or save something…",
                singleLine = false,
                minLines = 4,
                maxLines = 8,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasted = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                            if (pasted.isNotBlank()) {
                                text = if (text.isBlank()) pasted else "$text\n$pasted"
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        tint = RecallCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Paste clipboard", color = RecallCyan, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            RecallTextField(
                value = tags,
                onValueChange = { tags = it },
                label = "Tags",
                icon = Icons.Outlined.Tag,
                placeholder = "work, read later",
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RecallViolet.copy(alpha = 0.09f))
                    .border(1.dp, RecallViolet.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (favorite) RecallPink else RecallMuted,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Favorite", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep this close in your favorites.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RecallMuted,
                    )
                }
                Switch(
                    checked = favorite,
                    onCheckedChange = { favorite = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RecallInk950,
                        checkedTrackColor = RecallPink,
                        uncheckedThumbColor = RecallMuted,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
                    ),
                )
            }
            state.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = RecallDanger)
            }
            Spacer(Modifier.height(16.dp))
            RecallPrimaryButton(
                text = "Save capture",
                icon = Icons.Outlined.Save,
                onClick = { viewModel.saveQuickCapture(text, tags, favorite) },
                height = 48.dp,
            )
            TextButton(
                onClick = viewModel::closeComposer,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Cancel", color = RecallMuted)
            }
        }
    }
}

@Composable
fun ArchivedScreen(state: RecallUiState, viewModel: AppViewModel) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 20.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecallIconButton(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = viewModel::closeArchived,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Archived", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${state.archivedCount} ${if (state.archivedCount == 1) "capture" else "captures"} · restore anytime",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecallMuted,
                )
            }
        }
        MessageCard(state.error, state.notice, viewModel::clearMessages)
        if (state.archivedResults.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconBadge(icon = Icons.Outlined.Archive, tint = RecallCyan, size = 64.dp)
                Spacer(Modifier.height(16.dp))
                Text("Archive is empty", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Captures you archive will wait here. Nothing is permanently deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RecallMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.archivedResults, key = { it.id }) { result ->
                    ArchivedCard(
                        result = result,
                        onClick = { viewModel.openDetail(result.id) },
                        onRestore = { viewModel.restoreCapture(result.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedCard(
    result: SearchResult,
    onClick: () -> Unit,
    onRestore: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RecallInk800.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.snippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = RecallMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RecallIconButton(
                icon = Icons.Outlined.Restore,
                contentDescription = "Restore capture",
                onClick = onRestore,
                tint = RecallCyan,
                size = 38.dp,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (result.favorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = RecallPink,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                relativeTime(result.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = RecallFaint,
            )
            result.tags.take(2).forEach { tag ->
                TagChip(text = tag)
            }
            if (result.tags.size > 2) {
                Text(
                    text = "+${result.tags.size - 2}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallFaint,
                )
            }
        }
    }
}

@Composable
fun DetailDialog(
    detail: CaptureDetail,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
) {
    val context = LocalContext.current
    var newTag by rememberSaveable(detail.id) { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
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
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${relativeTime(detail.createdAt)} · ${if (detail.synced) "Synced" else "Local only"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecallFaint,
                    modifier = Modifier.weight(1f),
                )
                RecallIconButton(
                    icon = if (detail.favorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (detail.favorite) "Remove favorite" else "Add favorite",
                    onClick = onToggleFavorite,
                    tint = if (detail.favorite) RecallPink else RecallMuted,
                    size = 38.dp,
                )
            }

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

            Spacer(Modifier.height(14.dp))
            Text("Tags", style = MaterialTheme.typography.labelSmall, color = RecallCyan)
            Spacer(Modifier.height(6.dp))
            if (detail.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    detail.tags.forEach { tag ->
                        TagChip(text = tag, onRemove = { onRemoveTag(tag) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            RecallTextField(
                value = newTag,
                onValueChange = { newTag = it },
                label = null,
                icon = Icons.Outlined.Tag,
                placeholder = "Add a tag",
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add tag",
                        tint = RecallCyan,
                        modifier = Modifier
                            .clickable {
                                if (newTag.isNotBlank()) {
                                    onAddTag(newTag)
                                    newTag = ""
                                }
                            }
                            .padding(6.dp)
                            .size(18.dp),
                    )
                },
            )

            Spacer(Modifier.height(18.dp))
            if (detail.url != null && !detail.archived) {
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
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = if (detail.archived) onRestore else onArchive,
                ) {
                    Icon(
                        imageVector = if (detail.archived) Icons.Outlined.Restore else Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = if (detail.archived) RecallCyan else RecallMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (detail.archived) "Restore" else "Archive",
                        color = if (detail.archived) RecallCyan else RecallMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Close", color = RecallMuted, style = MaterialTheme.typography.labelMedium)
                }
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

private fun dayKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun dateLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(now, then) -> "Today"
        sameDay(yesterday, then) -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
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
