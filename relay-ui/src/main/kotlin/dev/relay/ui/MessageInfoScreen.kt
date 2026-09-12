package dev.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.core.Conversation
import dev.relay.core.LocalRelayIcons
import dev.relay.core.Message
import dev.relay.core.RelayApi
import dev.relay.core.RelayClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Message info" screen opened by long-pressing a message YOU sent — the WhatsApp-style twin of
 * [GroupDetailScreen]/[MediaGalleryScreen]'s own info screens: who has read this exact message
 * (and when) vs. who it's only been delivered to (and when), per-message-accurate via
 * [RelayClient.chat]'s getMessageReceipts (not the conversation-wide "last read" watermark).
 */
@Composable
fun MessageInfoScreen(
    client: RelayClient,
    conversation: Conversation,
    message: Message,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller; a nested
    // sub-screen reached via MessageThread, not a top-level entry point.
    val icons = LocalRelayIcons.current
    var receipts by remember(message.id) { mutableStateOf<RelayApi.MessageReceiptsResponse?>(null) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(message.id) {
        runCatching { client.chat.getMessageReceipts(conversation.id, message.id) }
            .onSuccess { receipts = it }
            .onFailure { error = "Could not load message info." }
    }

    fun nameFor(userId: String): String =
        conversation.members.firstOrNull { it.userId == userId }?.displayName
            ?: conversation.peer?.takeIf { it.userId == userId }?.displayName
            ?: userId

    fun avatarFor(userId: String): String? =
        conversation.members.firstOrNull { it.userId == userId }?.avatarUrl
            ?: conversation.peer?.takeIf { it.userId == userId }?.avatarUrl

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(icons.back, "Back") }
            Text("Message info", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            message.imageUrl?.let {
                                AsyncImage(
                                    model = it, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(180.dp).height(140.dp).clip(RoundedCornerShape(10.dp)),
                                )
                            }
                            message.fileThumbnailUrl?.let {
                                if (message.imageUrl == null) {
                                    AsyncImage(
                                        model = it, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(180.dp).height(140.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                }
                            }
                            if (message.body.isNotEmpty()) Text(message.body)
                            else if (message.imageUrl == null && message.fileThumbnailUrl == null) Text(message.fileName ?: "Attachment")
                        }
                    }
                }
            }
            if (error != null) {
                item { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            } else if (receipts == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                val r = receipts!!
                item { SectionHeader("Read by") }
                if (r.readBy.isEmpty()) {
                    item { Text("No one yet", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(r.readBy, key = { it.userId }) { entry ->
                        ReceiptRow(name = nameFor(entry.userId), avatarUrl = avatarFor(entry.userId), timestamp = entry.readAt)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
                item { SectionHeader("Delivered to") }
                if (r.deliveredTo.isEmpty()) {
                    item { Text("No one yet", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(r.deliveredTo, key = { it.userId }) { entry ->
                        ReceiptRow(name = nameFor(entry.userId), avatarUrl = avatarFor(entry.userId), timestamp = entry.deliveredAt)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ReceiptRow(name: String, avatarUrl: String?, timestamp: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name = name, url = avatarUrl, size = 36.dp)
        Text(name, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            formatReceiptTime(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val RECEIPT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.getDefault())

private fun formatReceiptTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    return instant.atZone(ZoneId.systemDefault()).format(RECEIPT_TIME_FORMATTER)
}
