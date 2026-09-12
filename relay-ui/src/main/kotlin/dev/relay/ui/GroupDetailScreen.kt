package dev.relay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.core.Conversation
import dev.relay.core.LocalRelayIcons
import dev.relay.core.Participant
import dev.relay.core.RelayClient
import dev.relay.core.RelayUser
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * "Group info" screen opened by tapping the group name in the thread header — the Android twin of
 * packages/web/react/src/components/GroupDetailModal.tsx: photo, name, member count + created
 * date, admin (creator-only) edit/add/remove, and per-member leave/mute/clear/media actions.
 */
@Composable
fun GroupDetailScreen(
    client: RelayClient,
    conversation: Conversation,
    onBack: () -> Unit,
    onPickAdd: (suspend () -> List<String>?)? = null,
    onSearchPeople: (suspend (query: String) -> List<RelayUser>)? = null,
    modifier: Modifier = Modifier,
) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller; GroupDetailScreen
    // is a nested sub-screen reached via MessageThread, not a top-level entry point, so it has no
    // `icons` param of its own and just reads whatever's ambient (or the default).
    val icons = LocalRelayIcons.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var participants by remember(conversation.id) { mutableStateOf<List<Participant>?>(null) }
    var creatorId by remember(conversation.id) { mutableStateOf(conversation.creatorId) }
    var conv by remember(conversation.id) { mutableStateOf(conversation) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    var addingBusy by remember { mutableStateOf(false) }
    var leaveBusy by remember { mutableStateOf(false) }
    var clearBusy by remember { mutableStateOf(false) }
    var muteBusy by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<Participant?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var showAddParticipants by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(conversation.name ?: "") }
    var editPhoto by remember { mutableStateOf(conversation.photoUrl) }

    fun load() {
        scope.launch {
            runCatching { client.chat.getParticipants(conversation.id) }
                .onSuccess { r ->
                    // Admin (the creator) always shown first, everyone else keeps the server's join order.
                    participants = r.creatorId?.let { cid -> r.participants.sortedBy { it.userId != cid } } ?: r.participants
                    creatorId = r.creatorId ?: creatorId
                }
                .onFailure { error = "Could not load participants." }
        }
    }
    LaunchedEffect(conversation.id) { load() }
    LaunchedEffect(client) {
        client.chat.state.collect { s -> s.conversation(conversation.id)?.let { conv = it } }
    }

    val myUserId = client.userId
    val isAdmin = myUserId != null && creatorId != null && myUserId == creatorId

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                loadPickedMedia(context, uri)
                    .onSuccess { picked -> if (picked is PickedAttachment.Image) editPhoto = picked.dataUrl }
                    .onFailure { error = it.message ?: "Could not read that image." }
            }
        }
    }

    fun handleRemove(userId: String) {
        confirmRemove = null
        busyUserId = userId
        error = null
        scope.launch {
            runCatching { client.chat.removeMember(conversation.id, userId) }
                .onSuccess { load() }
                .onFailure { error = "Could not remove that member." }
            busyUserId = null
        }
    }

    fun handleAdd() {
        if (onPickAdd == null) return
        scope.launch {
            addingBusy = true
            error = null
            val userIds = runCatching { onPickAdd() }.getOrNull()
            if (!userIds.isNullOrEmpty()) {
                runCatching { client.chat.addMembers(conversation.id, userIds) }
                    .onSuccess { load() }
                    .onFailure { error = "Could not add those members." }
            }
            addingBusy = false
        }
    }

    fun handleLeave() {
        confirmLeave = false
        leaveBusy = true
        error = null
        scope.launch {
            runCatching { client.chat.deleteConversation(conversation.id) }
                .onSuccess { onBack() }
                .onFailure { error = "Could not leave the group."; leaveBusy = false }
        }
    }

    fun handleClear() {
        confirmClear = false
        clearBusy = true
        error = null
        scope.launch {
            runCatching { client.chat.clearHistory(conversation.id) }
                .onSuccess { onBack() }
                .onFailure { error = "Could not clear the chat."; clearBusy = false }
        }
    }

    fun handleToggleMute() {
        muteBusy = true
        error = null
        scope.launch {
            runCatching { client.chat.muteConversation(conversation.id, !conv.muted) }
                .onFailure { error = "Could not update notifications." }
            muteBusy = false
        }
    }

    fun handleSaveEdit() {
        val name = editName.trim()
        if (name.isEmpty()) { error = "Group name cannot be empty."; return }
        saveBusy = true
        error = null
        scope.launch {
            runCatching {
                client.chat.updateGroup(
                    conversation.id,
                    name = if (name == conv.name) null else name,
                    photoUrl = if (editPhoto == conv.photoUrl) null else editPhoto,
                )
            }
                .onSuccess { editing = false }
                .onFailure { error = "Could not save changes." }
            saveBusy = false
        }
    }

    if (showMedia) {
        MediaGalleryScreen(client = client, conversationId = conversation.id, onBack = { showMedia = false }, modifier = modifier)
        return
    }
    if (showAddParticipants && onSearchPeople != null) {
        AddParticipantsScreen(
            client = client,
            conversation = conversation,
            onSearchPeople = onSearchPeople,
            onDismiss = { showAddParticipants = false },
            onAdded = { load() },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(icons.back, "Back") }
            Text("Group info", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            item {
                if (!editing) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(name = conv.title, url = conv.photoUrl, size = 72.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(conv.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${conv.memberCount} members · Created ${formatCreatedDate(conv.createdAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isAdmin) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { editName = conv.name ?: ""; editPhoto = conv.photoUrl; editing = true }) {
                                Text("Edit name & photo")
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.padding(bottom = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Avatar(name = editName.ifBlank { "Group" }, url = editPhoto, size = 72.dp)
                            }
                        }
                        TextButton(onClick = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text("Change photo")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { if (it.length <= 80) editName = it },
                            label = { Text("Group name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { editing = false }, enabled = !saveBusy) { Text("Cancel") }
                            TextButton(onClick = { handleSaveEdit() }, enabled = !saveBusy) { Text(if (saveBusy) "Saving…" else "Save") }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            item {
                GroupedCard {
                    ActionRow(icon = icons.photo, label = "Media, links & docs", onClick = { showMedia = true }, trailing = {
                        Icon(icons.chevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                    RowDivider()
                    ActionRow(
                        icon = icons.muteNotifications,
                        label = "Mute notifications",
                        onClick = { if (!muteBusy) handleToggleMute() },
                        trailing = { Switch(checked = conv.muted, onCheckedChange = { if (!muteBusy) handleToggleMute() }, enabled = !muteBusy) },
                    )
                    RowDivider()
                    ActionRow(icon = icons.delete, label = "Clear chat", onClick = { confirmClear = true }, enabled = !clearBusy, color = MaterialTheme.colorScheme.error)
                    RowDivider()
                    ActionRow(
                        icon = icons.leaveGroup,
                        label = "Leave group",
                        onClick = { confirmLeave = true },
                        enabled = !leaveBusy,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                GroupedCard {
                    if (isAdmin && (onSearchPeople != null || onPickAdd != null)) {
                        ActionRow(
                            icon = icons.addPeople,
                            label = if (addingBusy) "Adding…" else "Add people",
                            onClick = {
                                // Rich onSearchPeople callback wins: it opens the SDK-owned picker
                                // screen. Only the older raw onPickAdd callback falls back to
                                // today's behavior of calling it directly.
                                if (onSearchPeople != null) showAddParticipants = true
                                else if (!addingBusy) handleAdd()
                            },
                            enabled = !addingBusy,
                            color = MaterialTheme.colorScheme.primary,
                            circleColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                        RowDivider()
                    }
                    if (participants == null) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        participants!!.forEachIndexed { index, p ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(name = p.displayName ?: p.userId, url = p.avatarUrl, size = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.displayName ?: p.userId, maxLines = 1)
                                    if (p.userId == creatorId) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                                            Text(
                                                "Admin",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (myUserId != null && p.userId == myUserId) Text(" (You)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isAdmin && p.userId != creatorId) {
                                    TextButton(onClick = { confirmRemove = p }, enabled = busyUserId != p.userId) {
                                        Text(if (busyUserId == p.userId) "Removing…" else "Remove", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (index != participants!!.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove member") },
            text = { Text("Remove ${p.displayName ?: p.userId} from this group?") },
            confirmButton = { TextButton(onClick = { handleRemove(p.userId) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group") },
            text = { Text("You will stop receiving messages from this group.") },
            confirmButton = { TextButton(onClick = { handleLeave() }) { Text("Leave", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear chat") },
            text = { Text("This clears the chat history on your side only. Other members keep theirs.") },
            confirmButton = { TextButton(onClick = { handleClear() }) { Text("Clear", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Rounded, elevated section container — groups related rows the way iOS's Group info screen does
 *  (a "Settings app" style card), instead of a flat edge-to-edge list. */
@Composable
private fun GroupedCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A single row with a circular icon badge (leading) — mirrors iOS's grouped-list row style. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    circleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (circleColor != Color.Unspecified) circleColor else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = color, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private val MONTHS = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun formatCreatedDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val d = instant.atZone(ZoneId.systemDefault())
    return "${MONTHS[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"
}
