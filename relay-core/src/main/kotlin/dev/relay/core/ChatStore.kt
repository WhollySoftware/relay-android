package dev.relay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class Thread(
    val conversationId: String,
    val messages: List<Message> = emptyList(), // oldest → newest
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ChatSnapshot(
    val me: RelayUser? = null,
    val conversations: List<Conversation> = emptyList(), // newest activity first
    val conversationsLoaded: Boolean = false,
    val conversationsLoading: Boolean = false,
    val threads: Map<String, Thread> = emptyMap(),
    val typing: Map<String, List<String>> = emptyMap(),
    val readReceipts: Map<String, Map<String, String?>> = emptyMap(),
    val presence: Map<String, PresenceInfo> = emptyMap(),
) {
    val totalUnread: Int get() = conversations.sumOf { it.unreadCount }
    fun thread(id: String) = threads[id] ?: Thread(id)
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
}

/**
 * Reactive chat state as a StateFlow — the Kotlin twin of packages/web/core/src/store.ts:
 * optimistic sends de-duplicated against the socket echo by clientId, and a full resync after
 * every reconnect (the server never replays missed events). Collect `state` in Compose with
 * collectAsStateWithLifecycle().
 */
class ChatStore internal constructor(private val api: RelayApi, private val socket: RelaySocket) {
    private val _state = MutableStateFlow(ChatSnapshot())
    val state: StateFlow<ChatSnapshot> = _state.asStateFlow()
    val snapshot get() = _state.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val typingTimers = mutableMapOf<String, Job>()
    private val lastTypingSent = mutableMapOf<String, Long>()
    private val pendingFetch = mutableMapOf<String, Job>()
    private val threadLocks = mutableMapOf<String, Mutex>()
    @Volatile private var meId: String? = null
    @Volatile var viewingConversationId: String? = null; private set
    val userId get() = meId

    init {
        scope.launch {
            socket.signals.collect { signal ->
                when (signal) {
                    is RelaySocket.Signal.Event -> apply(signal.event)
                    RelaySocket.Signal.Reconnected -> resync()
                    RelaySocket.Signal.Connected -> viewingConversationId?.let { socket.send("event" to "viewing", "conversationId" to it) }
                    else -> {}
                }
            }
        }
    }

    internal fun setMe(user: RelayUser) { meId = user.userId; _state.update { it.copy(me = user) } }

    // ---- conversations -------------------------------------------------------

    suspend fun loadConversations(includeEmpty: Boolean = false): List<Conversation> {
        _state.update { it.copy(conversationsLoading = true) }
        try {
            val fromServer = api.listConversations(includeEmpty)
            val serverIds = fromServer.map { it.id }.toSet()
            val localEmpty = snapshot.conversations.filter { it.id !in serverIds && it.lastMessage == null && !includeEmpty }
            val list = sorted(fromServer + localEmpty)
            _state.update { it.copy(conversations = list, conversationsLoaded = true, conversationsLoading = false, presence = mergePresence(it.presence, list)) }
            return list
        } catch (e: Exception) {
            _state.update { it.copy(conversationsLoading = false) }
            throw e
        }
    }

    suspend fun openConversation(userId: String): Conversation = api.openConversation(userId).also { upsert(it) }
    suspend fun createGroup(name: String, userIds: List<String>, photoUrl: String? = null): Conversation = api.createGroup(name, userIds, photoUrl).also { upsert(it) }
    suspend fun updateGroup(id: String, name: String? = null, photoUrl: String? = null): Conversation = api.updateGroup(id, name, photoUrl).also { upsert(it) }
    suspend fun addMembers(id: String, userIds: List<String>) { api.addMembers(id, userIds); refreshConversation(id) }
    suspend fun removeMember(id: String, userId: String) { api.removeMember(id, userId); refreshConversation(id) }
    /** Full participant list + who created the group — used by the "Group info" screen. */
    suspend fun getParticipants(id: String): RelayApi.ParticipantsResponse = api.participants(id)
    /** Per-message-accurate read/delivery status for one message — a one-off fetch when "Message info" opens, no local caching. */
    suspend fun getMessageReceipts(conversationId: String, messageId: String): RelayApi.MessageReceiptsResponse = api.getMessageReceipts(conversationId, messageId)
    suspend fun deleteConversation(id: String) { api.deleteConversation(id); remove(id) }
    suspend fun clearHistory(id: String) { api.clearHistory(id); clearThread(id) }
    /** Optimistically patches local conversation state after the mute call succeeds — mirrors how other methods here patch after their API call. */
    suspend fun muteConversation(id: String, muted: Boolean) { val r = api.muteConversation(id, muted); patch(id) { it.copy(muted = r.muted) } }
    /** Same pagination as [loadMessages]/[loadOlderMessages], restricted to media/attachment messages — backs the "Media, links & docs" gallery. */
    suspend fun getMedia(id: String, before: String? = null, limit: Int = 50): MessagesPage = api.getMedia(id, before, limit)

    fun refreshConversation(id: String) {
        synchronized(pendingFetch) {
            if (pendingFetch[id]?.isActive == true) return
            pendingFetch[id] = scope.launch {
                try { upsert(api.getConversation(id)) }
                catch (e: RelayException) { if (e.status == 404) remove(id) }
                catch (_: Exception) {}
            }
        }
    }

    private fun upsert(c: Conversation) = _state.update { s -> val list = sorted(s.conversations.filter { it.id != c.id } + c); s.copy(conversations = list, presence = mergePresence(s.presence, listOf(c))) }
    private fun patch(id: String, change: (Conversation) -> Conversation) = _state.update { s -> s.copy(conversations = sorted(s.conversations.map { if (it.id == id) change(it) else it })) }
    private fun remove(id: String) = _state.update { s -> s.copy(conversations = s.conversations.filter { it.id != id }, threads = s.threads - id, typing = s.typing - id, readReceipts = s.readReceipts - id) }
    private fun sorted(list: List<Conversation>) = list.sortedWith(compareByDescending<Conversation> { it.lastMessageAt ?: it.createdAt }.thenByDescending { it.id.toLongOrNull() ?: 0 })
    private fun mergePresence(current: Map<String, PresenceInfo>, list: List<Conversation>): Map<String, PresenceInfo> {
        val m = current.toMutableMap()
        for (c in list) {
            c.peer?.let { p -> p.isOnline?.let { m[p.userId] = PresenceInfo(it, p.lastSeenAt) } }
            for (mem in c.members) m[mem.userId] = PresenceInfo(mem.isOnline, m[mem.userId]?.lastSeenAt)
        }
        return m
    }

    // ---- threads ---------------------------------------------------------------

    private fun setThread(id: String, change: (Thread) -> Thread) = _state.update { s -> s.copy(threads = s.threads + (id to change(s.thread(id)))) }
    private fun lock(id: String) = synchronized(threadLocks) { threadLocks.getOrPut(id) { Mutex() } }

    suspend fun loadMessages(id: String, force: Boolean = false): Thread {
        val t = snapshot.thread(id)
        if ((t.loaded && !force) || t.loading) return t
        setThread(id) { it.copy(loading = true, error = null) }
        try {
            val page = api.messages(id, limit = PAGE)
            val receipts = runCatching { api.readReceipts(id) }.getOrDefault(emptyList())
            val pending = snapshot.thread(id).messages.filter { it.isPending }
            setThread(id) { it.copy(messages = merge(page.messages, pending), hasMore = page.hasMore, loaded = true, loading = false) }
            _state.update { s -> s.copy(readReceipts = s.readReceipts + (id to receipts.associate { it.userId to it.lastReadAt })) }
        } catch (e: Exception) {
            setThread(id) { it.copy(loading = false, error = e.message) }
            throw e
        }
        return snapshot.thread(id)
    }

    suspend fun loadOlderMessages(id: String): Thread {
        val t = snapshot.thread(id)
        val oldest = t.messages.firstOrNull { !it.isPending }
        if (!t.hasMore || t.loading || oldest == null) return t
        setThread(id) { it.copy(loading = true) }
        try {
            val page = api.messages(id, before = oldest.id, limit = PAGE)
            setThread(id) { it.copy(messages = merge(page.messages + it.messages), hasMore = page.hasMore, loading = false) }
        } catch (e: Exception) {
            setThread(id) { it.copy(loading = false, error = e.message) }; throw e
        }
        return snapshot.thread(id)
    }

    /** Merge by id (server) and clientId (optimistic); oldest → newest, pending last. */
    internal fun merge(vararg lists: List<Message>): List<Message> {
        val byKey = LinkedHashMap<String, Message>()
        for (list in lists) for (m in list) {
            if (m.isPending && m.clientId != null && byKey.values.any { it.clientId == m.clientId && !it.isPending }) continue
            val key = if (m.isPending && m.clientId != null) "client:${m.clientId}" else m.id
            val existing = byKey[key]
            byKey[key] = if (existing != null) m.copy(clientId = m.clientId ?: existing.clientId) else m
            if (m.clientId != null && !m.isPending) byKey.remove("client:${m.clientId}")
        }
        return byKey.values.sortedWith(compareBy<Message> { if (it.isPending) 1 else 0 }.thenBy { if (it.isPending) it.createdAt else "" }.thenBy { it.id.toLongOrNull() ?: 0 })
    }

    private fun upsertMessage(id: String, m: Message) {
        val t = snapshot.thread(id)
        if (!t.loaded && t.messages.isEmpty()) return
        setThread(id) { it.copy(messages = merge(it.messages, listOf(m))) }
    }

    suspend fun sendMessage(id: String, text: String): Message = sendMessage(id, SendMessageInput(body = text))

    suspend fun sendMessage(id: String, input: SendMessageInput): Message {
        val clientId = input.clientId ?: "c_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val req = input.copy(clientId = clientId)
        val replyTarget = input.replyToId?.let { rid -> snapshot.thread(id).messages.firstOrNull { it.id == rid } }
        val optimistic = Message(
            id = "pending:$clientId", conversationId = id, senderId = meId ?: "", body = input.body ?: "",
            createdAt = java.time.Instant.now().toString(), imageUrl = input.imageUrl, audioUrl = input.audioUrl,
            audioDurationSec = input.audioDurationSec,
            fileUrl = input.fileUrl, fileName = input.fileName, fileSizeBytes = input.fileSizeBytes,
            fileThumbnailUrl = input.fileThumbnailUrl, fileDurationSec = input.fileDurationSec,
            replyTo = replyTarget?.let { ReplyPreview(it.id, it.senderId, it.body, it.deleted) },
            clientId = clientId, status = MessageStatus.SENDING,
        )
        setThread(id) { it.copy(messages = merge(it.messages, listOf(optimistic))) }
        try {
            val m = api.sendMessage(id, req).copy(clientId = clientId)
            applyOwn(id, m)
            return m
        } catch (e: Exception) {
            setThread(id) { t -> t.copy(messages = t.messages.map { if (it.clientId == clientId) it.copy(status = MessageStatus.FAILED, error = e.message) else it }) }
            throw e
        }
    }

    suspend fun retryMessage(id: String, clientId: String): Message {
        val failed = snapshot.thread(id).messages.firstOrNull { it.clientId == clientId && it.status == MessageStatus.FAILED }
            ?: throw IllegalArgumentException("No failed message with clientId $clientId")
        setThread(id) { t -> t.copy(messages = t.messages.filter { it.clientId != clientId }) }
        return sendMessage(id, SendMessageInput(
            body = failed.body.ifEmpty { null }, imageUrl = failed.imageUrl, audioUrl = failed.audioUrl, audioDurationSec = failed.audioDurationSec,
            fileUrl = failed.fileUrl, fileName = failed.fileName, fileSizeBytes = failed.fileSizeBytes,
            fileThumbnailUrl = failed.fileThumbnailUrl, fileDurationSec = failed.fileDurationSec,
            replyToId = failed.replyTo?.id, clientId = clientId,
        ))
    }

    fun discardMessage(id: String, clientId: String) = setThread(id) { t -> t.copy(messages = t.messages.filter { it.clientId != clientId }) }
    suspend fun editMessage(id: String, messageId: String, body: String): Message = api.editMessage(id, messageId, body).also { applyUpdate(id, it) }
    suspend fun deleteMessage(id: String, messageId: String): Message = api.deleteMessage(id, messageId).also { applyUpdate(id, it) }

    suspend fun markRead(id: String) {
        if (snapshot.conversation(id)?.unreadCount == 0) return
        patch(id) { it.copy(unreadCount = 0) }
        api.markRead(id)
    }

    /** Throttled — safe on every keystroke. */
    fun sendTyping(id: String) {
        val now = System.currentTimeMillis()
        synchronized(lastTypingSent) { if (now - (lastTypingSent[id] ?: 0) < TYPING_THROTTLE_MS) return; lastTypingSent[id] = now }
        socket.send("event" to "typing", "conversationId" to id)
    }

    /** Which thread is on screen: incoming messages there are marked read; pass null when leaving. */
    fun setViewing(id: String?) {
        viewingConversationId = id
        socket.send("event" to "viewing", "conversationId" to (id ?: ""))
        if (id != null) scope.launch { runCatching { markRead(id) } }
    }

    private fun applyOwn(id: String, m: Message) { upsertMessage(id, m.copy(status = MessageStatus.SENT)); bump(id, m, false) }
    private fun applyUpdate(id: String, m: Message) {
        setThread(id) { t -> t.copy(messages = t.messages.map { if (it.id == m.id) m else it }) }
        patch(id) { c -> if (c.lastMessage?.id == m.id) c.copy(lastMessage = preview(m)) else c }
    }
    private fun bump(id: String, m: Message, incrementUnread: Boolean) {
        if (snapshot.conversation(id) == null) { refreshConversation(id); return }
        patch(id) { it.copy(lastMessage = preview(m), lastMessageAt = m.createdAt, unreadCount = if (incrementUnread) it.unreadCount + 1 else it.unreadCount) }
    }
    private fun preview(m: Message): MessagePreview {
        val kind = when {
            m.deleted -> "deleted"
            m.imageUrl != null && m.body.isEmpty() -> "image"
            m.audioUrl != null && m.body.isEmpty() -> "audio"
            m.fileUrl != null && m.body.isEmpty() -> if (m.isVideo) "video" else "file"
            else -> "text"
        }
        val body = when (kind) {
            "deleted" -> ""; "image" -> "📷 Photo"; "audio" -> "🎤 Voice message"
            "video" -> "🎬 Video"; "file" -> "📎 ${m.fileName ?: "File"}"
            else -> m.body
        }
        return MessagePreview(m.id, m.senderId, kind, body, m.createdAt)
    }
    private fun clearThread(id: String) { setThread(id) { it.copy(messages = emptyList(), hasMore = false, loaded = true) }; patch(id) { it.copy(lastMessage = null, unreadCount = 0) } }

    // ---- realtime ------------------------------------------------------------------

    internal fun apply(e: RelayEvent) {
        when (e) {
            is RelayEvent.Connected -> meId = e.userId
            is RelayEvent.ChatMessage -> {
                val m = e.message.copy(clientId = e.clientId ?: e.message.clientId)
                if (m.senderId == meId) applyOwn(e.conversationId, m) else {
                    upsertMessage(e.conversationId, m)
                    val viewing = viewingConversationId == e.conversationId
                    bump(e.conversationId, m, !viewing)
                    clearTyping(e.conversationId, m.senderId)
                    if (viewing) scope.launch { runCatching { api.markRead(e.conversationId) } }
                }
            }
            is RelayEvent.ChatMessageUpdated -> applyUpdate(e.conversationId, e.message)
            is RelayEvent.ChatRead -> if (e.userId == meId) patch(e.conversationId) { it.copy(unreadCount = 0) }
                else _state.update { s -> s.copy(readReceipts = s.readReceipts + (e.conversationId to (s.readReceipts[e.conversationId].orEmpty() + (e.userId to e.lastReadAt)))) }
            is RelayEvent.Typing -> if (e.userId != meId) {
                _state.update { s -> val l = s.typing[e.conversationId].orEmpty(); if (e.userId in l) s else s.copy(typing = s.typing + (e.conversationId to l + e.userId)) }
                val key = "${e.conversationId}:${e.userId}"
                synchronized(typingTimers) { typingTimers[key]?.cancel(); typingTimers[key] = scope.launch { delay(TYPING_TTL_MS); clearTyping(e.conversationId, e.userId) } }
            }
            is RelayEvent.Presence -> _state.update { s ->
                s.copy(presence = s.presence + (e.userId to PresenceInfo(e.online, e.lastSeenAt)), conversations = s.conversations.map { c ->
                    var out = c
                    if (c.peer?.userId == e.userId) out = out.copy(peer = c.peer.copy(isOnline = e.online, lastSeenAt = e.lastSeenAt))
                    if (c.members.any { it.userId == e.userId }) out = out.copy(members = c.members.map { if (it.userId == e.userId) it.copy(isOnline = e.online) else it })
                    out
                })
            }
            is RelayEvent.ConversationCreated -> refreshConversation(e.conversationId)
            is RelayEvent.ConversationUpdated -> patch(e.conversationId) { it.copy(name = e.name, photoUrl = e.photoUrl) }
            is RelayEvent.ConversationDeleted -> remove(e.conversationId)
            is RelayEvent.ConversationCleared -> clearThread(e.conversationId)
            is RelayEvent.ConversationMuted -> patch(e.conversationId) { it.copy(muted = e.muted) }
            is RelayEvent.MembersAdded -> refreshConversation(e.conversationId)
            is RelayEvent.MemberRemoved -> refreshConversation(e.conversationId)
            is RelayEvent.MemberLeft -> refreshConversation(e.conversationId)
            else -> {}
        }
    }

    private fun clearTyping(id: String, userId: String) {
        synchronized(typingTimers) { typingTimers.remove("$id:$userId")?.cancel() }
        _state.update { s -> val l = s.typing[id] ?: return@update s; if (userId !in l) s else s.copy(typing = s.typing + (id to l - userId)) }
    }

    internal suspend fun resync() {
        if (snapshot.conversationsLoaded) runCatching { loadConversations() }
        for (t in snapshot.threads.values.filter { it.loaded }) {
            val page = runCatching { api.messages(t.conversationId, limit = PAGE) }.getOrNull() ?: continue
            val receipts = runCatching { api.readReceipts(t.conversationId) }.getOrDefault(emptyList())
            setThread(t.conversationId) { cur -> val overlaps = page.messages.any { m -> cur.messages.any { it.id == m.id } }; cur.copy(messages = merge(cur.messages, page.messages), hasMore = if (overlaps) cur.hasMore else page.hasMore) }
            _state.update { s -> s.copy(readReceipts = s.readReceipts + (t.conversationId to receipts.associate { it.userId to it.lastReadAt })) }
        }
    }

    internal fun reset() {
        synchronized(typingTimers) { typingTimers.values.forEach { it.cancel() }; typingTimers.clear() }
        lastTypingSent.clear(); viewingConversationId = null; meId = null
        _state.value = ChatSnapshot()
    }

    private companion object { const val PAGE = 50; const val TYPING_TTL_MS = 4000L; const val TYPING_THROTTLE_MS = 2500L }
}
