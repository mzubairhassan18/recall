package com.recall.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Recall", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${state.count} captures",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = viewModel::saveClipboard) { Text("Paste") }
                TextButton(onClick = viewModel::sync) {
                    Text(if (state.syncing) "Syncing…" else "Sync")
                }
                TextButton(onClick = viewModel::openSettings) { Text("Settings") }
                TextButton(onClick = viewModel::lock) { Text("Lock") }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search your memory") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    TextButton(onClick = { viewModel.setQuery("") }) { Text("Clear") }
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.mode == RecallUiState.MODE_SEARCH,
                onClick = { viewModel.setMode(RecallUiState.MODE_SEARCH) },
                label = { Text("Search") },
            )
            FilterChip(
                selected = state.mode == RecallUiState.MODE_TIMELINE,
                onClick = { viewModel.setMode(RecallUiState.MODE_TIMELINE) },
                label = { Text("Timeline") },
            )
        }

        if (state.mode == RecallUiState.MODE_TIMELINE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    null to "All",
                    "url" to "Links",
                    "text" to "Text",
                    "screenshot" to "Shots",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = state.typeFilter == value,
                        onClick = { viewModel.setTypeFilter(value) },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = state.domain,
                onValueChange = viewModel::setDomain,
                placeholder = { Text("Filter by domain (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (state.pendingScreenshots > 0) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.pendingScreenshots} screenshot(s) held",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    TextButton(onClick = viewModel::savePendingScreenshots) { Text("Save all") }
                    TextButton(onClick = viewModel::discardPendingScreenshots) { Text("Discard") }
                }
            }
        }

        MessageCard(state.error, state.notice, viewModel::clearMessages)

        val terms = remember(state.query) { Normalize.normalizeQuery(state.query).terms }
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::sync,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (state.results.isEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { EmptyState(Modifier.fillParentMaxSize()) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when {
                    state.syncing -> "Syncing…"
                    state.localOnly -> "Local-only vault"
                    state.lastSyncAt > 0 -> "Synced ${relativeTime(state.lastSyncAt)}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Encrypted at rest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Share text or images to Recall, use the selection menu, paste from the clipboard, " +
                "or turn on screenshot proposals in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(result: SearchResult, terms: List<String>, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            result.thumbnailB64?.let { encoded ->
                Thumbnail(encoded, 72.dp)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                result.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.snippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                HighlightedSnippet(result.snippet, terms)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "${relativeTime(result.createdAt)} · ${typeLabel(result.type)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Thumbnail(encoded: String, size: Dp) {
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
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
private fun HighlightedSnippet(text: String, terms: List<String>) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val highlight = MaterialTheme.colorScheme.primary
    val annotated: AnnotatedString = buildAnnotatedString {
        append(text)
        for (range in ranges) {
            if (range.first < text.length && range.last < text.length) {
                addStyle(
                    SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
    Text(annotated, style = style, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
fun DetailDialog(detail: CaptureDetail, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(detail.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                detail.thumbnail?.let { bytes ->
                    val bitmap = remember(bytes) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Text(
                    "${relativeTime(detail.createdAt)} · ${typeLabel(detail.type)} · " +
                        if (detail.synced) "synced" else "local only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                detail.text?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                detail.ocrText?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                detail.url?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            if (detail.url != null) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.url)))
                    }
                }) { Text("Open link") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (detail.url != null) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
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
