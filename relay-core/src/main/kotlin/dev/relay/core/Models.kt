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

/** Per-project module gating, returned alongside `user` from GET /users/me. A host that does
 *  nothing (older service without this field, or a project with nothing disabled) sees every
 *  module enabled — the defaults below match today's fully-open behavior. */
@Serializable
data class RelayModules(
    val chat: Boolean = true,
    val audioCalls: Boolean = true,
    val videoCalls: Boolean = true,
    val chatAttachments: Boolean = true,
    val chatVoiceMessages: Boolean = true,
    val push: Boolean = true,
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
    /** Whether the CURRENT user has muted this conversation — personal preference, not visible to others. */
    val muted: Boolean = false,
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
    /** A generic attachment (document, video, anything from a file picker) — mutually exclusive with imageUrl/audioUrl. */
    val fileUrl: String? = null,
    val fileName: String? = null,
    /** Inferred server-side for a data: fileUrl; null for an http(s) one unless the sender declared it. */
    val fileMime: String? = null,
    val fileSizeBytes: Long? = null,
    /** A client-extracted preview frame — only ever set for a video fileUrl. */
    val fileThumbnailUrl: String? = null,
    val fileDurationSec: Int? = null,
    val replyTo: ReplyPreview? = null,
    /** Client-generated id for optimistic sends; echoed by the server. */
    val clientId: String? = null,
    /** Local only: set on optimistic messages while in flight. */
    @kotlinx.serialization.Transient val status: MessageStatus? = null,
    @kotlinx.serialization.Transient val error: String? = null,
) {
    val isPending: Boolean get() = status == MessageStatus.SENDING || status == MessageStatus.FAILED
    val isVideo: Boolean get() = fileUrl != null && (fileMime ?: "").startsWith("video/")
    val isPdf: Boolean get() = fileUrl != null && fileMime == "application/pdf"
}

@Serializable
data class SendMessageInput(
    val body: String? = null,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    /** A generic attachment — http(s) URL, or a data URL under 14MB. Requires fileName. */
    val fileUrl: String? = null,
    val fileName: String? = null,
    /** Only used for an http(s) fileUrl — a data: fileUrl's size is computed server-side. */
    val fileSizeBytes: Long? = null,
    /** A client-extracted preview frame for a video fileUrl — http(s) URL, or an image data URL under 400KB. */
    val fileThumbnailUrl: String? = null,
    val fileDurationSec: Int? = null,
    val replyToId: String? = null,
    val clientId: String? = null,
)

@Serializable
data class Participant(val userId: String, val displayName: String? = null, val avatarUrl: String? = null, val isOnline: Boolean = false, val lastSeenAt: String? = null, val joinedAt: String)

@Serializable
data class ReadReceipt(val userId: String, val lastReadAt: String? = null)

@Serializable
data class MessagesPage(val messages: List<Message>, val hasMore: Boolean)

/** Open Graph metadata for a URL found in a message — enough to render a WhatsApp/social-app-style
 *  preview card under the bubble. Fetched server-side via RelayApi.linkPreview; null fields mean
 *  the page didn't declare that piece of metadata. */
@Serializable
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

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
    /**
     * This app's own applicationId (e.g. `BuildConfig.APPLICATION_ID`, or `context.packageName`).
     * Sent as X-App-Package-Id on every request — lets the service enforce an optional per-project
     * app allowlist (PATCH /projects/me/settings' androidPackageIds), so a public key copied out
     * of this app doesn't work in an unrelated one. Omit to send no header; the service only
     * checks it when that allowlist is non-empty.
     */
    val packageId: String? = null,
    val webSocketUrl: String? = null,
    val pingIntervalMs: Long = 25_000,
    val maxBackoffMs: Long = 30_000,
    val logger: ((String) -> Unit)? = null,
    /**
     * Opt-in verbose diagnostic logging. When `true` AND [logger] is non-null, the SDK emits
     * connection lifecycle, REST request/response, gateway event, and call lifecycle lines through
     * [logger] — see RelayDebugLog.kt. Redaction is designed in from the start: these lines never
     * include auth tokens, TURN credentials, message content/attachment URLs, or user display
     * names/avatars. Defaults to `false`, which is fully backward compatible with prior behavior
     * (only the two existing minimal connection/reconnect messages fire).
     */
    val debug: Boolean = false,
) {
    companion object {
        fun withStaticToken(baseUrl: String, publicKey: String, token: String) = RelayConfig(baseUrl, publicKey, { token })
    }
}
