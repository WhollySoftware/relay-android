package dev.relay.core

import kotlinx.serialization.Serializable

// Wire models — field-for-field the same as protocol/openapi.yaml. `userId` is ALWAYS the host
// app's own user id.

@Serializable
data class RelayUser(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isOnline: Boolean? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class GroupMember(val userId: String, val displayName: String? = null, val avatarUrl: String? = null, val isOnline: Boolean = false)

@Serializable
data class MessagePreview(val id: String, val senderId: String, val kind: String, val body: String, val createdAt: String)

@Serializable
data class Conversation(
    val id: String,
    val isGroup: Boolean,
    val name: String? = null,
    val photoUrl: String? = null,
    val creatorId: String? = null,
    val peer: RelayUser? = null,
    val members: List<GroupMember> = emptyList(),
    val memberCount: Int = 0,
    val lastMessage: MessagePreview? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val createdAt: String,
) {
    val title: String get() = if (isGroup) name ?: "Group" else peer?.displayName ?: peer?.userId ?: "Conversation"
}

@Serializable
data class ReplyPreview(val id: String, val senderId: String, val body: String, val deleted: Boolean)

enum class MessageStatus { SENDING, SENT, FAILED }

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String = "",
    val createdAt: String,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    val replyTo: ReplyPreview? = null,
    /** Client-generated id for optimistic sends; echoed by the server. */
    val clientId: String? = null,
    /** Local only: set on optimistic messages while in flight. */
    @kotlinx.serialization.Transient val status: MessageStatus? = null,
    @kotlinx.serialization.Transient val error: String? = null,
) {
    val isPending: Boolean get() = status == MessageStatus.SENDING || status == MessageStatus.FAILED
}

@Serializable
data class SendMessageInput(
    val body: String? = null,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    val replyToId: String? = null,
    val clientId: String? = null,
)

@Serializable
data class Participant(val userId: String, val displayName: String? = null, val avatarUrl: String? = null, val isOnline: Boolean = false, val lastSeenAt: String? = null, val joinedAt: String)

@Serializable
data class ReadReceipt(val userId: String, val lastReadAt: String? = null)

@Serializable
data class MessagesPage(val messages: List<Message>, val hasMore: Boolean)

data class PresenceInfo(val online: Boolean, val lastSeenAt: String?)

enum class ConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, CLOSED }

data class ConnectionSnapshot(val state: ConnectionState, val attempts: Int = 0, val lastError: String? = null)

class RelayException(val status: Int, val code: String, override val message: String) : Exception(message) {
    val isAuth get() = status == 401
}

/** A user token string, or a provider the client calls whenever the token must be (re)fetched. */
fun interface TokenProvider { suspend fun token(): String }

data class RelayConfig(
    /** e.g. https://relay.example.com */
    val baseUrl: String,
    /** The project's public key (pk_...). Safe to embed. */
    val publicKey: String,
    val tokenProvider: TokenProvider,
    val webSocketUrl: String? = null,
    val pingIntervalMs: Long = 25_000,
    val maxBackoffMs: Long = 30_000,
    val logger: ((String) -> Unit)? = null,
) {
    companion object {
        fun withStaticToken(baseUrl: String, publicKey: String, token: String) = RelayConfig(baseUrl, publicKey, { token })
    }
}
