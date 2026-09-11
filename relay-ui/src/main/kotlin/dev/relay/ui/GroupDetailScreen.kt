package dev.relay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.core.Conversation
import dev.relay.core.Participant
import dev.relay.core.RelayClient
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
    modifier: Modifier = Modifier,
) {
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
    var editing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(conversation.name ?: "") }
    var editPhoto by remember { mutableStateOf(conversation.photoUrl) }

    fun load() {
        scope.launch {
            runCatching { client.chat.getParticipants(conversation.id) }
                .onSuccess { r -> participants = r.participants; creatorId = r.creatorId ?: creatorId }
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

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Group info", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
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
                HorizontalDivider()
            }
            item {
                Column {
                    ActionRow(icon = "🖼️", label = "Media, links & docs", onClick = { showMedia = true })
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🔕", modifier = Modifier.width(32.dp))
                        Text("Mute notifications", modifier = Modifier.weight(1f))
                        Switch(checked = conv.muted, onCheckedChange = { if (!muteBusy) handleToggleMute() }, enabled = !muteBusy)
                    }
                    HorizontalDivider()
                    ActionRow(icon = "🧹", label = "Clear chat", onClick = { confirmClear = true }, enabled = !clearBusy)
                    ActionRow(
                        icon = "🚪",
                        label = "Leave group",
                        onClick = { confirmLeave = true },
                        enabled = !leaveBusy,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
            }
            if (isAdmin && onPickAdd != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (!addingBusy) handleAdd() }, enabled = !addingBusy) { Icon(Icons.Filled.Add, "Add people") }
                        Spacer(Modifier.width(4.dp))
                        Text(if (addingBusy) "Adding…" else "Add people", color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
            if (participants == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                items(participants!!, key = { it.userId }) { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(name = p.displayName ?: p.userId, url = p.avatarUrl, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName ?: p.userId, maxLines = 1)
                            if (p.userId == creatorId) {
                                Spacer(Modifier.width(6.dp))
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                    Text(
                                        "Admin",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
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

@Composable
private fun ActionRow(
    icon: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(icon, modifier = Modifier.width(28.dp))
            Text(label, color = color)
        }
    }
    HorizontalDivider()
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
