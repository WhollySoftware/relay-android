package dev.relay.core

/**
 * Redaction-first helpers backing [RelayConfig.debug] diagnostic logging.
 *
 * These exist so verbose logging can be added throughout the SDK without ever risking a leaked
 * secret: every call site funnels URLs through [redactedUrl]/[redactedHost] rather than logging
 * them raw, and [debugLog] is the only way debug lines get emitted at all. Never log a raw token,
 * TURN `credential`/`username`, `secretKey`, a message's `body`/`imageUrl`/`audioUrl`/`fileUrl`/
 * `fileName`, or a [RelayUser]'s `displayName`/`avatarUrl` — reference those only by length/count,
 * or as the literal `"<redacted>"`.
 */

/** Scheme + host + path only — strips any query string entirely. The gateway WS URL embeds
 *  `?key=...&token=...`, so this (never the raw URL) is what's safe to log. Falls back to a
 *  placeholder if [url] isn't a parseable URL at all. */
fun redactedUrl(url: String): String = runCatching { url.substringBefore('?') }.getOrDefault("<url>")

/** Scheme + host (+ port) only, no path or query — used for the terser "connecting to..." line. */
internal fun redactedHost(url: String): String = runCatching {
    val uri = java.net.URI(url)
    val host = uri.host ?: return@runCatching "<url>"
    buildString {
        uri.scheme?.let { append(it).append("://") }
        append(host)
        if (uri.port != -1) append(':').append(uri.port)
    }
}.getOrDefault("<url>")

/** Emits [message] through [RelayConfig.logger] only when [RelayConfig.debug] is enabled and a
 *  logger is actually set. The lambda is never invoked otherwise, so building the diagnostic
 *  string costs nothing on the (default) non-debug hot path. Public (not `internal`) so other
 *  modules in this SDK (relay-call, relay-ui) can log through the same redaction-first helper. */
fun RelayConfig.debugLog(message: () -> String) {
    if (debug && logger != null) logger?.invoke(message())
}

/** A one-line, redaction-safe description of an incoming gateway event: its type plus only
 *  opaque, non-content identifiers (conversationId/messageId/callId/userId) that are already safe
 *  to log — never a message's body/imageUrl/audioUrl/fileUrl/fileName, and never a display name. */
internal fun eventLogName(event: RelayEvent): String = when (event) {
    is RelayEvent.Connected -> "connected userId=${event.userId}"
    is RelayEvent.Pong -> "pong"
    is RelayEvent.ChatMessage -> "chat_message conversationId=${event.conversationId} messageId=${event.message.id}"
    is RelayEvent.ChatMessageUpdated -> "chat_message_updated conversationId=${event.conversationId} messageId=${event.message.id}"
    is RelayEvent.ChatRead -> "chat_read conversationId=${event.conversationId} userId=${event.userId}"
    is RelayEvent.Typing -> "typing conversationId=${event.conversationId} userId=${event.userId}"
    is RelayEvent.Presence -> "presence userId=${event.userId} online=${event.online}"
    is RelayEvent.ConversationCreated -> "conversation_created conversationId=${event.conversationId}"
    is RelayEvent.ConversationUpdated -> "conversation_updated conversationId=${event.conversationId}"
    is RelayEvent.ConversationDeleted -> "conversation_deleted conversationId=${event.conversationId}"
    is RelayEvent.ConversationCleared -> "conversation_cleared conversationId=${event.conversationId}"
    is RelayEvent.ConversationMuted -> "conversation_muted conversationId=${event.conversationId} muted=${event.muted}"
    is RelayEvent.MembersAdded -> "members_added conversationId=${event.conversationId} count=${event.userIds.size}"
    is RelayEvent.MemberRemoved -> "member_removed conversationId=${event.conversationId}"
    is RelayEvent.MemberLeft -> "member_left conversationId=${event.conversationId}"
    is RelayEvent.ModulesUpdated -> "modules_updated"
    is RelayEvent.Unknown -> "unknown ${event.event}"
}

/** "videoCalls=false, push=true" for just the keys that changed between [old] and [new] — booleans
 *  aren't sensitive, so it's safe to log their values (unlike everything else in [RelayModules]'s
 *  neighborhood, this is the one payload type debug logging can dump in full). Null when nothing
 *  changed. */
internal fun modulesDiffLogLine(old: RelayModules, new: RelayModules): String? {
    val changed = buildList {
        if (old.chat != new.chat) add("chat=${new.chat}")
        if (old.audioCalls != new.audioCalls) add("audioCalls=${new.audioCalls}")
        if (old.videoCalls != new.videoCalls) add("videoCalls=${new.videoCalls}")
        if (old.chatAttachments != new.chatAttachments) add("chatAttachments=${new.chatAttachments}")
        if (old.chatVoiceMessages != new.chatVoiceMessages) add("chatVoiceMessages=${new.chatVoiceMessages}")
        if (old.push != new.push) add("push=${new.push}")
    }
    return changed.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
