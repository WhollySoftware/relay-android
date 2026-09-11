package dev.relay.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
 * date, and (creator only — there's no per-member role, admin-ness is purely
 * `conversation.creatorId`, same as web) add/remove participants.
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
    var participants by remember(conversation.id) { mutableStateOf<List<Participant>?>(null) }
    var creatorId by remember(conversation.id) { mutableStateOf(conversation.creatorId) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    var addingBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            runCatching { client.chat.getParticipants(conversation.id) }
                .onSuccess { r -> participants = r.participants; creatorId = r.creatorId ?: creatorId }
                .onFailure { error = "Could not load participants." }
        }
    }
    LaunchedEffect(conversation.id) { load() }

    val myUserId = client.userId
    val isAdmin = myUserId != null && creatorId != null && myUserId == creatorId

    fun handleRemove(userId: String) {
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

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Group info", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(name = conversation.title, url = conversation.photoUrl, size = 72.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(conversation.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${conversation.memberCount} members · Created ${formatCreatedDate(conversation.createdAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
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
                            TextButton(onClick = { handleRemove(p.userId) }, enabled = busyUserId != p.userId) {
                                Text(if (busyUserId == p.userId) "Removing…" else "Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
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
