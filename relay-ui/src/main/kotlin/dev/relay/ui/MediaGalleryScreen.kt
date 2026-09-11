package dev.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.relay.core.Message
import dev.relay.core.RelayClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

private val MONTHS = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun monthLabel(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val d = instant.atZone(ZoneId.systemDefault())
    val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
    return when {
        d.year == now.year && d.monthValue == now.monthValue -> "This month"
        d.year == now.year -> MONTHS[d.monthValue - 1]
        else -> "${MONTHS[d.monthValue - 1]} ${d.year}"
    }
}

private fun isVisual(m: Message): Boolean = m.imageUrl != null || (m.fileUrl != null && (m.fileMime ?: "").startsWith("video/"))

private data class MediaGroup(val label: String, val items: List<Message>)

private fun groupByMonth(items: List<Message>): List<MediaGroup> {
    val groups = mutableListOf<MediaGroup>()
    for (item in items) {
        val label = monthLabel(item.createdAt)
        val last = groups.lastOrNull()
        if (last != null && last.label == label) {
            groups[groups.size - 1] = last.copy(items = last.items + item)
        } else {
            groups.add(MediaGroup(label, listOf(item)))
        }
    }
    return groups
}

/**
 * "Media, links & docs" gallery — the Android twin of
 * packages/web/react/src/components/MediaGalleryModal.tsx: Media tab shows a grid of image/video
 * thumbnails grouped by month; Docs tab shows a list of generic attachments. Backed by
 * ChatStore.getMedia (GET /conversations/:id/messages?kind=media), same before/limit pagination
 * as the regular message list. No "Links" tab yet — same reason as web: the server doesn't tag
 * which plain-text messages contain a URL.
 */
@Composable
fun MediaGalleryScreen(
    client: RelayClient,
    conversationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var tab by remember(conversationId) { mutableStateOf(0) } // 0 = Media, 1 = Docs
    var messages by remember(conversationId) { mutableStateOf<List<Message>?>(null) }
    var hasMore by remember(conversationId) { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(before: String? = null) {
        if (before != null) loadingMore = true
        scope.launch {
            runCatching { client.chat.getMedia(conversationId, before = before, limit = 60) }
                .onSuccess { page ->
                    messages = if (before != null) page.messages + (messages ?: emptyList()) else page.messages
                    hasMore = page.hasMore
                }
                .onFailure { error = "Could not load media." }
            loadingMore = false
        }
    }
    LaunchedEffect(conversationId) { load() }

    val all = messages
    val media = remember(all) { all?.filter { isVisual(it) }?.asReversed() ?: emptyList() }
    val docs = remember(all) { all?.filterNot { isVisual(it) }?.asReversed() ?: emptyList() }
    val mediaGroups = remember(media) { groupByMonth(media) }
    val docGroups = remember(docs) { groupByMonth(docs) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Media, links & docs", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Media") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Docs") })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        if (all == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        if (tab == 0) {
            if (media.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos or videos yet.") }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (group in mediaGroups) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { m ->
                            Box(Modifier.aspectRatio(1f).padding(2.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                val thumb = m.imageUrl ?: m.fileThumbnailUrl
                                if (thumb != null) {
                                    AsyncImage(model = thumb, contentDescription = null, modifier = Modifier.fillMaxSize())
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("▶") }
                                }
                                if (m.imageUrl == null) {
                                    Text("▶", color = Color.White, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                    if (hasMore) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            LoadEarlierRow(loadingMore) { load(before = all.firstOrNull()?.id) }
                        }
                    }
                }
            }
        } else {
            if (docs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No documents yet.") }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    for (group in docGroups) {
                        item {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { m ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (m.audioUrl != null) "🎤" else "📎", modifier = Modifier.size(28.dp))
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(m.fileName ?: (if (m.audioUrl != null) "Voice message" else "File"), maxLines = 1)
                                    m.fileSizeBytes?.let {
                                        Text("${it / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (hasMore) {
                        item { LoadEarlierRow(loadingMore) { load(before = all.firstOrNull()?.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadEarlierRow(loading: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        TextButton(onClick = onClick, enabled = !loading) { Text(if (loading) "Loading…" else "Load earlier") }
    }
}
