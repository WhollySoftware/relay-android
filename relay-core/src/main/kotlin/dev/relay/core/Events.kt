package dev.relay.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Server → client gateway frames (protocol/events.md). Unknown events are kept raw. */
sealed class RelayEvent {
    data class Connected(val userId: String, val serverTime: String?) : RelayEvent()
    object Pong : RelayEvent()
    data class ChatMessage(val conversationId: String, val message: Message, val clientId: String?) : RelayEvent()
    data class ChatMessageUpdated(val conversationId: String, val message: Message) : RelayEvent()
    data class ChatRead(val conversationId: String, val userId: String, val lastReadAt: String) : RelayEvent()
    data class Typing(val conversationId: String, val userId: String) : RelayEvent()
    data class Presence(val userId: String, val online: Boolean, val lastSeenAt: String?) : RelayEvent()
    data class ConversationCreated(val conversationId: String) : RelayEvent()
    data class ConversationUpdated(val conversationId: String, val name: String?, val photoUrl: String?) : RelayEvent()
    data class ConversationDeleted(val conversationId: String) : RelayEvent()
    data class ConversationCleared(val conversationId: String) : RelayEvent()
    data class ConversationMuted(val conversationId: String, val muted: Boolean) : RelayEvent()
    data class MembersAdded(val conversationId: String, val userIds: List<String>) : RelayEvent()
    data class MemberRemoved(val conversationId: String, val userId: String) : RelayEvent()
    data class MemberLeft(val conversationId: String, val userId: String) : RelayEvent()
    /** Project-wide module-gating push (super admin changed a project's flags). Always carries the
     *  full, current module set — not a partial patch. */
    data class ModulesUpdated(val modules: RelayModules) : RelayEvent()
    /** Anything else (e.g. call_* frames handled by relay-call). */
    data class Unknown(val event: String, val payload: JsonObject) : RelayEvent()

    companion object {
        fun decode(obj: JsonObject): RelayEvent? {
            val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return null
            fun s(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
            fun msg() = obj["message"]?.let { RelayJson.json.decodeFromJsonElement(Message.serializer(), it) }
            return when (event) {
                "connected" -> Connected(s("userId") ?: return null, s("serverTime"))
                "pong" -> Pong
                "chat_message" -> ChatMessage(s("conversationId") ?: return null, msg() ?: return null, s("clientId"))
                "chat_message_updated" -> ChatMessageUpdated(s("conversationId") ?: return null, msg() ?: return null)
                "chat_read" -> ChatRead(s("conversationId") ?: return null, s("userId") ?: return null, s("lastReadAt") ?: return null)
                "typing" -> Typing(s("conversationId") ?: return null, s("userId") ?: return null)
                "presence" -> Presence(s("userId") ?: return null, obj["online"]?.jsonPrimitive?.booleanOrNull ?: return null, s("lastSeenAt"))
                "conversation_created" -> ConversationCreated(s("conversationId") ?: return null)
                "conversation_updated" -> ConversationUpdated(s("conversationId") ?: return null, s("name"), s("photoUrl"))
                "conversation_deleted" -> ConversationDeleted(s("conversationId") ?: return null)
                "conversation_cleared" -> ConversationCleared(s("conversationId") ?: return null)
                "conversation_muted" -> ConversationMuted(s("conversationId") ?: return null, obj["muted"]?.jsonPrimitive?.booleanOrNull ?: return null)
                "members_added" -> MembersAdded(s("conversationId") ?: return null, obj["userIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList())
                "member_removed" -> MemberRemoved(s("conversationId") ?: return null, s("userId") ?: return null)
                "member_left" -> MemberLeft(s("conversationId") ?: return null, s("userId") ?: return null)
                "modules_updated" -> ModulesUpdated(obj["modules"]?.jsonObject?.let { RelayJson.json.decodeFromJsonElement(RelayModules.serializer(), it) } ?: return null)
                else -> Unknown(event, obj)
            }
        }
    }
}

object RelayJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
}
