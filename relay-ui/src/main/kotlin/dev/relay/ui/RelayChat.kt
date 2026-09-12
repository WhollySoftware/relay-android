package dev.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.relay.core.ChatSnapshot
import dev.relay.core.Conversation
import dev.relay.core.LocalRelayColors
import dev.relay.core.LocalRelayIcons
import dev.relay.core.LocalRelayTypography
import dev.relay.core.Message
import dev.relay.core.MessageStatus
import dev.relay.core.RelayClient
import dev.relay.core.RelayColors
import dev.relay.core.RelayIcons
import dev.relay.core.RelayTypography
import dev.relay.core.RelayUser
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// --------------------------------------------------------------------------------------------
// (MessageComposer's attachment picking lives in Attachments.kt — PickedAttachment,
// loadPickedMedia, loadPickedFile, newCameraCaptureUri, loadCameraCapture)
// --------------------------------------------------------------------------------------------

/**
 * The whole chat UI: list → thread → composer, with a connection banner. Drop it in a screen:
 *
 *     RelayChat(client = relay)
 */
@Composable
fun RelayChat(
    client: RelayClient,
    modifier: Modifier = Modifier,
    onPickGroupMembers: (suspend () -> List<String>?)? = null,
    onSearchPeople: (suspend (query: String) -> List<RelayUser>)? = null,
    icons: RelayIcons? = null,
    typography: RelayTypography? = null,
) {
    // Resolved once here and re-provided, so every Relay composable nested underneath (through
    // ConversationList/MessageThread) sees a non-null LocalRelayColors.current whether or not the
    // host ever wraps anything in RelayTheme — see RelayColors.kt.
    val resolvedColors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
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
            else MessageThread(client, conversationId = id, onBack = { selected = null }, onPickGroupMembers = onPickGroupMembers, onSearchPeople = onSearchPeople)
        }
    }
}

@Composable
fun ConversationList(client: RelayClient, onSelect: (Conversation) -> Unit, includeEmpty: Boolean = true, modifier: Modifier = Modifier, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // See RelayChat's identical resolution — ConversationList is also callable as a host's own
    // top-level screen, without going through RelayChat.
    val resolvedColors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
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
fun MessageThread(
    client: RelayClient,
    conversationId: String,
    onBack: (() -> Unit)? = null,
    headerActions: @Composable (Conversation) -> Unit = {},
    modifier: Modifier = Modifier,
    onPickGroupMembers: (suspend () -> List<String>?)? = null,
    onSearchPeople: (suspend (query: String) -> List<RelayUser>)? = null,
    icons: RelayIcons? = null,
    typography: RelayTypography? = null,
) {
    // See RelayChat's identical resolution — MessageThread is also callable as a host's own
    // top-level screen. The actual body is extracted to MessageThreadContent below because it
    // returns early (group-detail / message-info sub-screens), and a `return` isn't legal inside
    // CompositionLocalProvider's `content` lambda parameter.
    val resolvedColors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
        MessageThreadContent(client, conversationId, onBack, headerActions, modifier, onPickGroupMembers, onSearchPeople)
    }
}

@Composable
private fun MessageThreadContent(
    client: RelayClient,
    conversationId: String,
    onBack: (() -> Unit)?,
    headerActions: @Composable (Conversation) -> Unit,
    modifier: Modifier,
    onPickGroupMembers: (suspend () -> List<String>?)?,
    onSearchPeople: (suspend (query: String) -> List<RelayUser>)?,
) {
    val icons = LocalRelayIcons.current
    val state by client.chat.state.collectAsStateWithLifecycle()
    val thread = state.thread(conversationId)
    val conversation = state.conversation(conversationId)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var forwarding by remember { mutableStateOf<Message?>(null) }
    var scrolledInitiallyFor by remember { mutableStateOf<String?>(null) }
    var showGroupDetail by rememberSaveable { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf<Message?>(null) }
    // Driven off scroll state rather than intercepting touch input, so it doesn't fight the
    // message bubbles' own tap-to-reply/long-press gestures — only a real fling/drag hides the
    // keyboard, not a plain tap on the list.
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling) keyboardController?.hide() }
    }
    DisposableEffect(conversationId) {
        client.chat.setViewing(conversationId)
        scope.launch { runCatching { client.chat.loadMessages(conversationId) } }
        onDispose { if (client.chat.viewingConversationId == conversationId) client.chat.setViewing(null) }
    }
    // Index of the last real row — accounts for the conditional "Load earlier messages" item at
    // index 0 (below) so this lands on the actual last message, not one row short.
    val lastItemIndex = (if (thread.hasMore) 1 else 0) + thread.messages.size - 1
    // First time this conversation's messages arrive, jump straight to the bottom with no
    // animation — an animated scroll here raced LazyColumn's very first layout pass often enough
    // to visibly under-scroll, leaving the thread opening on older messages instead of the latest.
    LaunchedEffect(conversationId, thread.messages.isNotEmpty()) {
        if (thread.messages.isNotEmpty() && scrolledInitiallyFor != conversationId) {
            listState.scrollToItem(lastItemIndex)
            scrolledInitiallyFor = conversationId
        }
    }
    // Every later arrival (a new message while already viewing) animates instead, since by then
    // the list is already laid out and the animation reads as a natural "new message" nudge.
    LaunchedEffect(thread.messages.lastOrNull()?.id) {
        if (scrolledInitiallyFor == conversationId && thread.messages.isNotEmpty()) {
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    // imePadding here (not on the LazyColumn/composer individually) is what keeps the header
    // pinned: the header is a fixed-height first child, so when the keyboard eats into this
    // Column's available height only the LazyColumn (weight(1f)) and composer below it shrink —
    // without it the whole screen (header included) got pushed up by the OS's window resize.
    if (showGroupDetail && conversation != null && conversation.isGroup) {
        GroupDetailScreen(
            client = client,
            conversation = conversation,
            onBack = { showGroupDetail = false },
            onPickAdd = onPickGroupMembers,
            onSearchPeople = onSearchPeople,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    showInfo?.let { m ->
        if (conversation != null) {
            MessageInfoScreen(
                client = client,
                conversation = conversation,
                message = m,
                onBack = { showInfo = null },
                modifier = modifier.fillMaxSize(),
            )
            return
        }
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(icons.back, "Back") }
            conversation?.let { c ->
                Box {
                    Avatar(name = c.title, url = if (c.isGroup) c.photoUrl else c.peer?.avatarUrl, size = 36.dp)
                    if (!c.isGroup) c.peer?.isOnline?.let { PresenceDot(it, Modifier.align(Alignment.BottomEnd)) }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(
                Modifier.weight(1f).let { m -> if (conversation?.isGroup == true) m.clickable { showGroupDetail = true } else m },
            ) {
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
                    api = client.api,
                    senderName = if (conversation?.isGroup == true && !isOwn) conversation.members.firstOrNull { it.userId == m.senderId }?.displayName ?: m.senderId else null,
                    seen = if (isLastOwn && m.status != MessageStatus.SENDING && m.status != MessageStatus.FAILED) seen else null,
                    onRetry = { m.clientId?.let { cid -> scope.launch { runCatching { client.chat.retryMessage(conversationId, cid) } } } },
                    onDiscard = { m.clientId?.let { client.chat.discardMessage(conversationId, it) } },
                    onReply = { replyTo = m },
                    onDelete = if (isOwn) ({ scope.launch { runCatching { client.chat.deleteMessage(conversationId, m.id) } } }) else null,
                    onForward = { forwarding = m },
                    onShowInfo = if (isOwn) ({ showInfo = m }) else null)
            }
        }
        val typing = state.typing[conversationId].orEmpty()
        if (typing.isNotEmpty()) Text("${typing.joinToString { u -> conversation?.members?.firstOrNull { it.userId == u }?.displayName ?: conversation?.peer?.displayName ?: u }} ${if (typing.size == 1) "is" else "are"} typing…",
            Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MessageComposer(client, conversationId, replyTo = replyTo, onCancelReply = { replyTo = null })
    }
    forwarding?.let { m ->
        ForwardPickerDialog(
            conversations = state.conversations.filter { it.id != conversationId },
            onDismiss = { forwarding = null },
            onPick = { targetId ->
                forwarding = null
                scope.launch {
                    runCatching {
                        client.chat.sendMessage(
                            targetId,
                            dev.relay.core.SendMessageInput(
                                body = m.body.ifEmpty { null }, imageUrl = m.imageUrl, audioUrl = m.audioUrl, audioDurationSec = m.audioDurationSec,
                                fileUrl = m.fileUrl, fileName = m.fileName, fileSizeBytes = m.fileSizeBytes,
                                fileThumbnailUrl = m.fileThumbnailUrl, fileDurationSec = m.fileDurationSec,
                            ),
                        )
                    }
                }
            },
        )
    }
}

/** Simple conversation picker for "Forward" — every other conversation the user is already in.
 *  No new-conversation flow here; forwarding to someone you haven't messaged yet is just opening
 *  that chat and pasting, same as most chat apps' plain forward-to-existing-chat picker. */
@Composable
private fun ForwardPickerDialog(conversations: List<Conversation>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        ) {
            Text("Forward to…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (conversations.isEmpty()) {
                Text("No other conversations yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(conversations, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(c.title, if (c.isGroup) c.photoUrl else c.peer?.avatarUrl, size = 36.dp)
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// Call summary lines are posted by the service as plain text ("📞 Video call · 0:30",
// "📵 Video call cancelled", "📵 Missed call", "📵 Declined call") — a leading 📞 is a completed
// call, 📵 is missed/declined/cancelled. Recognized here purely by that prefix so no protocol
// change was needed to give them their own pill instead of a normal chat bubble.
private fun callMessageInfo(body: String): Pair<String, Boolean>? = when {
    body.startsWith("📞 ") -> body.removePrefix("📞 ") to false
    body.startsWith("📵 ") -> body.removePrefix("📵 ") to true
    else -> null
}

@Composable
private fun CallMessageBubble(label: String, missed: Boolean, isVideo: Boolean, time: String, isOwn: Boolean) {
    // Safe non-null: only ever reached from MessageBubbleContent, which resolves/provides
    // LocalRelayColors before calling this.
    val colors = LocalRelayColors.current!!
    val icons = LocalRelayIcons.current
    val tint = if (missed) colors.danger else colors.online
    Row(
        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(999.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(
                if (isVideo) icons.cameraOn else icons.callAnswer,
                contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp),
            )
        }
        Column {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A voice-message bubble's play/pause + elapsed-or-duration row. */
@Composable
fun VoiceMessageRow(messageId: String, audioUrl: String, durationSec: Int?, tint: Color) {
    // Standalone-safe: LocalRelayIcons always has a non-null default (see RelayIcons.kt), so
    // this needs no ancestor to have provided anything, unlike LocalRelayColors.
    val icons = LocalRelayIcons.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val playing = ChatAudioPlayer.isPlaying(messageId)
    val seconds = if (playing) ChatAudioPlayer.elapsedSeconds else (durationSec ?: 0)
    DisposableEffect(messageId) { onDispose { if (ChatAudioPlayer.isPlaying(messageId)) ChatAudioPlayer.stop() } }
    Row(
        Modifier.clickable { ChatAudioPlayer.toggle(context, scope, messageId, audioUrl) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (playing) icons.pauseVideo else icons.playVideo, if (playing) "Pause" else "Play voice message", tint = tint)
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 13.sp, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, isOwn: Boolean, api: dev.relay.core.RelayApi? = null, senderName: String? = null, seen: Boolean? = null, onRetry: () -> Unit = {}, onDiscard: () -> Unit = {}, onReply: () -> Unit = {}, onDelete: (() -> Unit)? = null, onForward: ((Message) -> Unit)? = null, onShowInfo: (() -> Unit)? = null, icons: RelayIcons? = null, typography: RelayTypography? = null) {
    // See RelayChat's identical resolution — MessageBubble is also callable standalone (e.g. a
    // host building its own message list). The body is extracted to MessageBubbleContent below
    // because it returns early for call-summary messages, and `return` isn't legal inside
    // CompositionLocalProvider's `content` lambda parameter.
    val resolvedColors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val resolvedIcons = icons ?: LocalRelayIcons.current
    val resolvedTypography = typography ?: LocalRelayTypography.current
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    CompositionLocalProvider(
        LocalRelayColors provides resolvedColors,
        LocalRelayIcons provides resolvedIcons,
        LocalDensity provides Density(density.density, density.fontScale * resolvedTypography.fontScale),
        LocalTextStyle provides textStyle.copy(fontFamily = resolvedTypography.fontFamily ?: textStyle.fontFamily),
    ) {
        MessageBubbleContent(m, isOwn, api, senderName, seen, onRetry, onDiscard, onReply, onDelete, onForward, onShowInfo)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleContent(m: Message, isOwn: Boolean, api: dev.relay.core.RelayApi?, senderName: String?, seen: Boolean?, onRetry: () -> Unit, onDiscard: () -> Unit, onReply: () -> Unit, onDelete: (() -> Unit)?, onForward: ((Message) -> Unit)?, onShowInfo: (() -> Unit)?) {
    // Safe non-null default (see RelayIcons.kt) — no wrapping requirement, unlike LocalRelayColors.
    val icons = LocalRelayIcons.current
    callMessageInfo(m.body)?.let { (label, missed) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
            CallMessageBubble(label, missed, isVideo = label.contains("Video", ignoreCase = true), time = time(m.createdAt), isOwn = isOwn)
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
        if (senderName != null) Text(senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        val bg = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        var showActions by remember { mutableStateOf(false) }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Box {
            Column(
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(bg)
                    .combinedClickable(
                        enabled = !m.deleted && !m.isPending,
                        onClick = onReply,
                        onLongClick = { showActions = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
            m.replyTo?.let { r -> Text((if (r.deleted) "Message deleted" else r.body.ifEmpty { "Attachment" }), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 2) }
            when {
                m.deleted -> Text("This message was deleted", color = fg.copy(alpha = 0.7f))
                else -> {
                    m.imageUrl?.let {
                        AsyncImage(
                            model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.width(220.dp).height(180.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    m.audioUrl?.let { url -> VoiceMessageRow(m.clientId ?: m.id, url, m.audioDurationSec, fg) }
                    m.fileUrl?.let { url ->
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val openFile = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
                        if (m.isVideo) {
                            Box(
                                Modifier.width(220.dp).height(140.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)).clickable { openFile() },
                            ) {
                                m.fileThumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                Icon(icons.playVideo, "Play video", Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
                                m.fileDurationSec?.let { sec ->
                                    Text(
                                        "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}", fontSize = 10.sp, color = Color.White,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        } else if (m.isPdf && m.fileThumbnailUrl != null) {
                            Box(
                                Modifier.width(220.dp).height(140.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)).clickable { openFile() },
                            ) {
                                AsyncImage(model = m.fileThumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Icon(icons.attach, "PDF document", Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
                                Text(
                                    m.fileName ?: "Document", fontSize = 10.sp, color = Color.White, maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        } else {
                            Row(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable { openFile() }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(icons.attach, "File", tint = fg)
                                Column {
                                    Text(m.fileName ?: "File", fontSize = 13.sp, color = fg, maxLines = 1)
                                    m.fileSizeBytes?.let { Text(formatFileSize(it), fontSize = 10.sp, color = fg.copy(alpha = 0.7f)) }
                                }
                            }
                        }
                    }
                    if (m.body.isNotEmpty()) Text(m.body, color = fg)
                    if (api != null && !m.isPending) firstUrl(m.body)?.let { url -> LinkPreviewCard(api, url, isOwn, fg) }
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.editedAt != null && !m.deleted) Text("edited", fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
                Text(time(m.createdAt), fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
                if (m.status == MessageStatus.SENDING) Text("⏱", fontSize = 10.sp, color = fg.copy(alpha = 0.7f))
            }
            }
            androidx.compose.material3.DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                if (!m.deleted && (m.body.isNotEmpty() || m.fileName != null)) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            showActions = false
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.body.ifEmpty { m.fileName.orEmpty() }))
                        },
                        leadingIcon = { Icon(icons.copy, null) },
                    )
                }
                if (!m.deleted && !m.isPending) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Reply") }, onClick = { showActions = false; onReply() }, leadingIcon = { Icon(icons.reply, null) })
                }
                if (onForward != null && !m.deleted && !m.isPending) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Forward") }, onClick = { showActions = false; onForward(m) }, leadingIcon = { Icon(icons.forward, null) })
                }
                if (!m.deleted && !m.isPending) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            showActions = false
                            val shareText = m.body.ifEmpty { m.fileUrl ?: m.imageUrl ?: m.audioUrl ?: "" }
                            if (shareText.isNotEmpty()) {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, shareText) }
                                context.startActivity(android.content.Intent.createChooser(send, null))
                            }
                        },
                        leadingIcon = { Icon(icons.share, null) },
                    )
                }
                if (onShowInfo != null && isOwn && !m.deleted && !m.isPending) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Message info") }, onClick = { showActions = false; onShowInfo() }, leadingIcon = { Icon(icons.info, null) })
                }
                if (onDelete != null && !m.deleted && !m.isPending && isOwn) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Delete") }, onClick = { showActions = false; onDelete() }, leadingIcon = { Icon(icons.delete, null) })
                }
            }
        }
        if (m.status == MessageStatus.FAILED) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Not sent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
        // WhatsApp-style receipt: single check = sent, double check = seen (green) — this SDK has
        // no distinct "delivered" signal (only sent vs. read-receipt "seen"), so there's no gray
        // double-check tier here.
        if (seen != null) Icon(
            if (seen) icons.checkRead else icons.checkSent, contentDescription = if (seen) "Seen" else "Sent",
            modifier = Modifier.padding(end = 6.dp).size(14.dp),
            // Safe non-null: MessageBubble (the public wrapper) always resolves/provides
            // LocalRelayColors before calling this (MessageBubbleContent).
            tint = if (seen) LocalRelayColors.current!!.online else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Text field + send button, plus an attach menu (Camera / Gallery / File) mirroring DevBattel's
 * chat composer.
 *
 * Permissions: gallery (`PickVisualMedia`, Android's system Photo Picker) and file
 * (`GetContent`) need NOTHING — both are out-of-process pickers, this code never gets broader
 * media/storage access than the one item the user picked, on any API level. Only **Camera**
 * needs anything from the host app: add `<uses-permission android:name="android.permission.CAMERA"/>`
 * to its own manifest and this composable requests it at runtime the first time Camera is
 * tapped — deliberately not declared in relay-ui's own manifest, so a chat-only app that never
 * uses the camera isn't forced to carry that permission. (The FileProvider Camera needs to write
 * its capture into IS bundled in relay-ui's manifest already — that's plumbing, not a
 * user-facing permission, see its own doc comment.)
 */
@Composable
fun MessageComposer(client: RelayClient, conversationId: String, replyTo: Message? = null, onCancelReply: () -> Unit = {}) {
    // Safe non-null default (see RelayIcons.kt) — no wrapping requirement, unlike LocalRelayColors.
    val icons = LocalRelayIcons.current
    var text by rememberSaveable(conversationId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attachment by remember { mutableStateOf<PickedAttachment?>(null) }
    var loadingAttachment by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var dismissedPreviewUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var cameraCaptureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val voiceRecorder = rememberVoiceRecorder()

    fun handlePick(block: suspend () -> Result<PickedAttachment>) {
        loadingAttachment = true
        scope.launch {
            block().onSuccess { attachment = it }.onFailure { error = it.message }
            loadingAttachment = false
        }
    }

    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) handlePick { loadPickedMedia(context, uri) } }

    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) handlePick { loadPickedFile(context, uri) } }

    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { success -> val uri = cameraCaptureUri; if (success && uri != null) handlePick { loadCameraCapture(context, uri) } }

    var showCameraSettingsPrompt by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) { val uri = newCameraCaptureUri(context); cameraCaptureUri = uri; cameraLauncher.launch(uri) }
        // No dialog is left to (re-)show once the system has already asked and been refused —
        // the only way forward is the app's own permission screen in system Settings.
        else showCameraSettingsPrompt = true
    }

    fun launchCamera() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { val uri = newCameraCaptureUri(context); cameraCaptureUri = uri; cameraLauncher.launch(uri) }
        else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    var showMicSettingsPrompt by remember { mutableStateOf(false) }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voiceRecorder.start() else showMicSettingsPrompt = true }

    fun startRecording() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) voiceRecorder.start() else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    if (showMicSettingsPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMicSettingsPrompt = false },
            title = { Text("Permission needed") },
            text = { Text("Microphone access was denied. Enable it in Settings to record a voice message.") },
            confirmButton = {
                TextButton(onClick = {
                    showMicSettingsPrompt = false
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showMicSettingsPrompt = false }) { Text("Cancel") } },
        )
    }

    if (showCameraSettingsPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCameraSettingsPrompt = false },
            title = { Text("Permission needed") },
            text = { Text("Camera access was denied. Enable it in Settings to take a photo.") },
            confirmButton = {
                TextButton(onClick = {
                    showCameraSettingsPrompt = false
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showCameraSettingsPrompt = false }) { Text("Cancel") } },
        )
    }

    fun send() {
        val body = text.trim()
        val pending = attachment
        if (body.isEmpty() && pending == null) return
        text = ""; error = null; attachment = null; dismissedPreviewUrl = null
        scope.launch {
            val input = when (pending) {
                is PickedAttachment.Image -> dev.relay.core.SendMessageInput(body = body.ifEmpty { null }, imageUrl = pending.dataUrl, replyToId = replyTo?.id)
                is PickedAttachment.FileAttachment -> dev.relay.core.SendMessageInput(
                    body = body.ifEmpty { null }, fileUrl = pending.dataUrl, fileName = pending.name,
                    fileThumbnailUrl = pending.thumbnail, fileDurationSec = pending.durationSec, replyToId = replyTo?.id,
                )
                is PickedAttachment.Audio -> dev.relay.core.SendMessageInput(body = body.ifEmpty { null }, audioUrl = pending.dataUrl, audioDurationSec = pending.durationSec, replyToId = replyTo?.id)
                null -> dev.relay.core.SendMessageInput(body = body, replyToId = replyTo?.id)
            }
            try { client.chat.sendMessage(conversationId, input); onCancelReply() }
            catch (e: Exception) { error = e.message; attachment = pending }
        }
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        replyTo?.let { r -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Replying: ${if (r.deleted) "Message deleted" else r.body.ifEmpty { "Attachment" }}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); TextButton(onClick = onCancelReply) { Text("✕") } } }
        attachment?.let { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                when (a) {
                    is PickedAttachment.Image -> AsyncImage(model = a.dataUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                    is PickedAttachment.FileAttachment -> {
                        if (a.thumbnail != null) AsyncImage(model = a.thumbnail, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        else Icon(if (a.mime.startsWith("video/")) icons.playVideo else icons.attach, null, Modifier.size(44.dp))
                        Text(a.name, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    is PickedAttachment.Audio -> {
                        Icon(icons.recordVoice, null, Modifier.size(28.dp))
                        Text("Voice message · ${a.durationSec / 60}:${(a.durationSec % 60).toString().padStart(2, '0')}", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { attachment = null }) { Text("✕") }
            }
        }
        if (loadingAttachment) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text(" Preparing attachment…", style = MaterialTheme.typography.labelSmall) }
        if (attachment == null) {
            firstUrl(text)?.takeIf { it != dismissedPreviewUrl }?.let { url ->
                Box(Modifier.padding(bottom = 4.dp)) { ComposeLinkPreviewCard(client.api, url) { dismissedPreviewUrl = url } }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        val modules by client.modulesFlow.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.Bottom) {
            if (modules.chatAttachments) {
                Box {
                    IconButton(onClick = { showAttachMenu = true }) { Icon(icons.attach, "Attach") }
                    androidx.compose.material3.DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Camera") }, onClick = { showAttachMenu = false; launchCamera() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Gallery") }, onClick = { showAttachMenu = false; galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("File") }, onClick = { showAttachMenu = false; fileLauncher.launch("*/*") })
                    }
                }
            }
            if (voiceRecorder.isRecording) {
                Row(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icons.recordVoice, "Recording", tint = MaterialTheme.colorScheme.error)
                    Text(" ${voiceRecorder.elapsedSeconds / 60}:${(voiceRecorder.elapsedSeconds % 60).toString().padStart(2, '0')}", Modifier.weight(1f))
                    TextButton(onClick = { voiceRecorder.cancel() }) { Text("Cancel") }
                }
            } else {
                OutlinedTextField(value = text, onValueChange = { text = it; if (it.isNotBlank()) client.chat.sendTyping(conversationId) }, modifier = Modifier.weight(1f), placeholder = { Text("Message…") }, maxLines = 5, shape = RoundedCornerShape(20.dp))
            }
            if (voiceRecorder.isRecording) {
                IconButton(onClick = { voiceRecorder.finish()?.let { attachment = it } }) { Icon(icons.stopRecording, "Stop recording", tint = MaterialTheme.colorScheme.error) }
            } else if (text.isBlank() && attachment == null && modules.chatVoiceMessages) {
                IconButton(onClick = { startRecording() }) { Icon(icons.recordVoice, "Record voice message") }
            } else {
                IconButton(onClick = { send() }, enabled = text.isNotBlank() || attachment != null) { Icon(icons.send, "Send", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
fun Avatar(name: String?, url: String?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    // Standalone-safe (no reliance on an ancestor having wrapped anything): falls back to the
    // ambient MaterialTheme-derived default, exactly like every other public entry point.
    val colors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    val label = name.orEmpty()
    val hue = (label.fold(0) { h, c -> (h * 31 + c.code) and 0x7fffffff } % 360).toFloat()
    // Only the background's saturation/lightness are promoted to overridable tokens (their
    // defaults, 0.45f/0.92f, match today's literal exactly). The initials-text pair (0.5f/0.35f)
    // is left as a literal: it has no corresponding RelayColors field, and background vs. text
    // intentionally differ here, so there's nothing sensible to wire it to without adding a new
    // field the published spec doesn't define.
    Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, colors.avatarSaturation, colors.avatarLightness)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = label, modifier = Modifier.fillMaxSize())
        else Text(label.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifEmpty { "?" }, color = Color.hsv(hue, 0.5f, 0.35f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PresenceDot(online: Boolean, modifier: Modifier = Modifier) {
    // Standalone-safe, same pattern as Avatar above.
    val colors = LocalRelayColors.current ?: RelayColors.fromMaterialTheme(MaterialTheme.colorScheme)
    Box(modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(if (online) colors.online else Color.Gray))
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
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
