package dev.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.relay.core.ChatSnapshot
import dev.relay.core.Conversation
import dev.relay.core.Message
import dev.relay.core.MessageStatus
import dev.relay.core.RelayClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole chat UI: list → thread → composer, with a connection banner. Drop it in a screen:
 *
 *     RelayChat(client = relay)
 */
@Composable
fun RelayChat(client: RelayClient, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val connection by client.connection.collectAsStateWithLifecycle()
    LaunchedEffect(client) { runCatching { client.connect() } }
    Column(modifier.fillMaxSize()) {
        if (connection.state.name != "CONNECTED" && connection.state.name != "IDLE") {
            Text(
                when (connection.state.name) { "CONNECTING" -> "Connecting…"; "RECONNECTING" -> "Reconnecting…"; else -> "Disconnected" },
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val id = selected
        if (id == null) ConversationList(client, onSelect = { selected = it.id })
        else MessageThread(client, conversationId = id, onBack = { selected = null })
    }
}

@Composable
fun ConversationList(client: RelayClient, onSelect: (Conversation) -> Unit, includeEmpty: Boolean = true, modifier: Modifier = Modifier) {
    val state by client.chat.state.collectAsStateWithLifecycle()
    LaunchedEffect(client) { runCatching { client.chat.loadConversations(includeEmpty) } }
    Box(modifier.fillMaxSize()) {
        if (!state.conversationsLoaded && state.conversationsLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else if (state.conversationsLoaded && state.conversations.isEmpty()) Text("No conversations yet", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(state.conversations, key = { it.id }) { c ->
                ConversationRow(c, state, myUserId = client.userId, onClick = { onSelect(c) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ConversationRow(c: Conversation, state: ChatSnapshot, myUserId: String?, onClick: () -> Unit) {
    val typing = state.typing[c.id].orEmpty()
    val preview = when {
        typing.isNotEmpty() -> "typing…"
        c.lastMessage == null -> ""
        else -> (if (c.lastMessage!!.senderId == myUserId) "You: " else if (c.isGroup) (c.members.firstOrNull { it.userId == c.lastMessage!!.senderId }?.displayName ?: c.lastMessage!!.senderId) + ": " else "") +
            (if (c.lastMessage!!.kind == "deleted") "Message deleted" else c.lastMessage!!.body)
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(name = c.title, url = if (c.isGroup) c.photoUrl else c.peer?.avatarUrl, size = 44.dp)
            if (!c.isGroup) c.peer?.isOnline?.let { PresenceDot(it, Modifier.align(Alignment.BottomEnd)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(c.title, Modifier.weight(1f), fontWeight = if (c.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.lastMessageAt?.let { Text(relative(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (typing.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.unreadCount > 0) Badge { Text(if (c.unreadCount > 99) "99+" else "${c.unreadCount}") }
            }
        }
    }
}

@Composable
fun MessageThread(client: RelayClient, conversationId: String, onBack: (() -> Unit)? = null, headerActions: @Composable (Conversation) -> Unit = {}, modifier: Modifier = Modifier) {
    val state by client.chat.state.collectAsStateWithLifecycle()
    val thread = state.thread(conversationId)
    val conversation = state.conversation(conversationId)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var replyTo by remember { mutableStateOf<Message?>(null) }
    DisposableEffect(conversationId) {
        client.chat.setViewing(conversationId)
        scope.launch { runCatching { client.chat.loadMessages(conversationId) } }
        onDispose { if (client.chat.viewingConversationId == conversationId) client.chat.setViewing(null) }
    }
    LaunchedEffect(thread.messages.lastOrNull()?.id) { if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.size) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(conversation?.title ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (conversation?.isGroup == true) "${conversation.memberCount} members" else if (conversation?.peer?.isOnline == true) "Online" else "Offline"
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            conversation?.let { headerActions(it) }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
            if (thread.hasMore) item { TextButton(onClick = { scope.launch { runCatching { client.chat.loadOlderMessages(conversationId) } } }, enabled = !thread.loading) { Text(if (thread.loading) "Loading…" else "Load earlier messages") } }
            if (thread.loaded && thread.messages.isEmpty()) item { Text("Say hello 👋", Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(thread.messages, key = { _, m -> m.clientId ?: m.id }) { i, m ->
                val prev = thread.messages.getOrNull(i - 1)
                if (prev == null || day(prev.createdAt) != day(m.createdAt)) Text(day(m.createdAt), Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val isOwn = m.senderId == client.userId
                val receipts = state.readReceipts[conversationId].orEmpty()
                val isLastOwn = isOwn && thread.messages.drop(i + 1).none { it.senderId == client.userId }
                val seen = receipts.any { (u, at) -> u != client.userId && at != null && at >= m.createdAt }
                MessageBubble(m, isOwn,
                    senderName = if (conversation?.isGroup == true && !isOwn) conversation.members.firstOrNull { it.userId == m.senderId }?.displayName ?: m.senderId else null,
                    status = if (isLastOwn && m.status != MessageStatus.SENDING && m.status != MessageStatus.FAILED) (if (seen) "Seen" else "Sent") else null,
                    onRetry = { m.clientId?.let { cid -> scope.launch { runCatching { client.chat.retryMessage(conversationId, cid) } } } },
                    onDiscard = { m.clientId?.let { client.chat.discardMessage(conversationId, it) } },
                    onReply = { replyTo = m },
                    onDelete = if (isOwn) ({ scope.launch { runCatching { client.chat.deleteMessage(conversationId, m.id) } } }) else null)
            }
        }
        val typing = state.typing[conversationId].orEmpty()
        if (typing.isNotEmpty()) Text("${typing.joinToString { u -> conversation?.members?.firstOrNull { it.userId == u }?.displayName ?: conversation?.peer?.displayName ?: u }} ${if (typing.size == 1) "is" else "are"} typing…",
            Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MessageComposer(client, conversationId, replyTo = replyTo, onCancelReply = { replyTo = null })
    }
}

@Composable
fun MessageBubble(m: Message, isOwn: Boolean, senderName: String? = null, status: String? = null, onRetry: () -> Unit = {}, onDiscard: () -> Unit = {}, onReply: () -> Unit = {}, onDelete: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
        if (senderName != null) Text(senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        val bg = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(enabled = !m.deleted && !m.isPending, onClick = onReply).padding(horizontal = 12.dp, vertical = 8.dp)) {
            m.replyTo?.let { r -> Text((if (r.deleted) "Message deleted" else r.body.ifEmpty { "Attachment" }), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 2) }
            when {
                m.deleted -> Text("This message was deleted", color = fg.copy(alpha = 0.7f))
                else -> {
                    m.imageUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(10.dp))) }
                    if (m.body.isNotEmpty()) Text(m.body, color = fg)
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.editedAt != null && !m.deleted) Text("edited", fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
                Text(time(m.createdAt), fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
                if (m.status == MessageStatus.SENDING) Text("⏱", fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
            }
        }
        if (m.status == MessageStatus.FAILED) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Not sent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
        if (onDelete != null && !m.deleted && !m.isPending && isOwn) TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun MessageComposer(client: RelayClient, conversationId: String, replyTo: Message? = null, onCancelReply: () -> Unit = {}) {
    var text by rememberSaveable(conversationId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun send() {
        val body = text.trim(); if (body.isEmpty()) return
        text = ""; error = null
        scope.launch {
            try { client.chat.sendMessage(conversationId, dev.relay.core.SendMessageInput(body = body, replyToId = replyTo?.id)); onCancelReply() }
            catch (e: Exception) { error = e.message }
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        replyTo?.let { r -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Replying: ${if (r.deleted) "Message deleted" else r.body.ifEmpty { "Attachment" }}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); TextButton(onClick = onCancelReply) { Text("✕") } } }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(value = text, onValueChange = { text = it; if (it.isNotBlank()) client.chat.sendTyping(conversationId) }, modifier = Modifier.weight(1f), placeholder = { Text("Message…") }, maxLines = 5, shape = RoundedCornerShape(20.dp))
            IconButton(onClick = { send() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun Avatar(name: String?, url: String?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val label = name.orEmpty()
    val hue = (label.fold(0) { h, c -> (h * 31 + c.code) and 0x7fffffff } % 360).toFloat()
    Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.92f)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = label, modifier = Modifier.fillMaxSize())
        else Text(label.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifEmpty { "?" }, color = Color.hsv(hue, 0.5f, 0.35f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PresenceDot(online: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) Color(0xFF22C55E) else Color.Gray))
    }
}

private fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()
fun relative(iso: String, now: Instant = Instant.now()): String {
    val t = parse(iso) ?: return ""
    val s = (now.epochSecond - t.epochSecond).coerceAtLeast(0)
    return when { s < 45 -> "now"; s < 3600 -> "${(s / 60)}m"; s < 86_400 -> "${(s / 3600)}h"; s < 7 * 86_400 -> "${s / 86_400}d"; else -> DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(t) }
}
fun time(iso: String): String = parse(iso)?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(it) } ?: ""
fun day(iso: String): String {
    val t = parse(iso) ?: return ""
    val d = t.atZone(ZoneId.systemDefault()).toLocalDate(); val today = java.time.LocalDate.now()
    return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> DateTimeFormatter.ofPattern("MMM d").format(d) }
}
