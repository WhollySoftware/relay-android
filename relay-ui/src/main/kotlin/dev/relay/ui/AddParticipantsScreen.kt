package dev.relay.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.relay.core.Conversation
import dev.relay.core.LocalRelayIcons
import dev.relay.core.RelayClient
import dev.relay.core.RelayUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SDK-owned "Add participants" picker, opened from [GroupDetailScreen]'s "Add people" row when the
 * host supplies the richer [onSearchPeople] callback (in preference to the older raw [GroupDetailScreen.onPickAdd]
 * string-id callback, which stays untouched for hosts that want to build their own picker UI).
 *
 * [onSearchPeople] is invoked once with `""` as soon as this screen appears (a starting/suggested
 * list), then again ~250ms after the search field settles on each keystroke. Responses are matched
 * back to the request that produced them with a generation counter rather than coroutine
 * cancellation, so a slow earlier response can never clobber a faster later one.
 */
@Composable
fun AddParticipantsScreen(
    client: RelayClient,
    conversation: Conversation,
    onSearchPeople: suspend (query: String) -> List<RelayUser>,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller; a nested
    // sub-screen reached via GroupDetailScreen, not a top-level entry point.
    val icons = LocalRelayIcons.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RelayUser>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<List<RelayUser>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var addBusy by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    // Bumped on every search request (initial + each debounced keystroke); a request only applies
    // its result if this is still the latest generation when it completes — out-of-order responses
    // are discarded by this check, not by cancelling the coroutine that's awaiting them.
    var generation by remember { mutableStateOf(0) }

    fun runSearch(q: String, debounce: Boolean) {
        val myGeneration = ++generation
        loading = true
        scope.launch {
            if (debounce) delay(250)
            // Superseded by a newer keystroke while we were waiting out the debounce — don't even
            // issue the call.
            if (myGeneration != generation) return@launch
            val outcome = runCatching { onSearchPeople(q) }
            if (myGeneration != generation) return@launch
            loading = false
            outcome.onSuccess { searchError = null; results = it }
                .onFailure { searchError = "Could not search for people." }
        }
    }

    // The very first composition runs the "" starting-list lookup immediately; every later change
    // to `query` (user typing) goes through the debounce path.
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        if (firstRun) { firstRun = false; runSearch(query, debounce = false) }
        else runSearch(query, debounce = true)
    }

    fun toggle(u: RelayUser) {
        selected = if (selected.any { it.userId == u.userId }) selected.filterNot { it.userId == u.userId } else selected + u
    }

    fun handleAddPressed() {
        if (selected.isEmpty() || addBusy) return
        addBusy = true
        addError = null
        scope.launch {
            runCatching { client.chat.addMembers(conversation.id, selected.map { it.userId }) }
                .onSuccess { onAdded(); onDismiss() }
                .onFailure { addError = "Could not add those members." }
            addBusy = false
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(icons.back, "Cancel") }
            Text("Add participants", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            TextButton(onClick = { handleAddPressed() }, enabled = selected.isNotEmpty() && !addBusy) {
                Text(if (addBusy) "Adding…" else if (selected.isEmpty()) "Add" else "Add (${selected.size})")
            }
        }
        HorizontalDivider()
        addError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search people") },
            leadingIcon = { Icon(icons.search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(icons.close, "Clear") } },
            singleLine = true,
        )
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected.forEach { u -> SelectedChip(u, onRemove = { toggle(u) }) }
            }
        }
        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        val list = results
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(icons.searchOff, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("No one matches \"$query\"", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Try a different name or check the spelling.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(list, key = { it.userId }) { u ->
                    PersonRow(u, selected = selected.any { it.userId == u.userId }, onClick = { toggle(u) })
                }
            }
        }
    }
}

@Composable
private fun PersonRow(u: RelayUser, selected: Boolean, onClick: () -> Unit) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller.
    val icons = LocalRelayIcons.current
    val lastSeenAt = u.lastSeenAt
    val subtitle = when {
        u.isOnline == true -> "Online"
        !lastSeenAt.isNullOrBlank() -> "Last seen ${relative(lastSeenAt)}"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(name = u.displayName ?: u.userId, url = u.avatarUrl, size = 40.dp)
            if (u.isOnline == true) PresenceDot(true, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.displayName ?: u.userId, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        if (selected) Icon(icons.checkCircleSelected, "Selected", tint = MaterialTheme.colorScheme.primary)
        else Icon(icons.checkCircleUnselected, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun SelectedChip(u: RelayUser, onRemove: () -> Unit) {
    // Plain non-null default (see RelayIcons.kt) — safe regardless of caller.
    val icons = LocalRelayIcons.current
    val firstName = u.displayName?.trim()?.substringBefore(' ')?.ifBlank { null } ?: u.userId
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(999.dp)) {
        Row(
            Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Avatar(name = u.displayName ?: u.userId, url = u.avatarUrl, size = 24.dp)
            Text(firstName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                icons.close,
                "Remove ${u.displayName ?: u.userId}",
                modifier = Modifier.size(14.dp).clip(CircleShape).clickable(onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
