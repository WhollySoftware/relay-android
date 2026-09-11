package dev.relay.call

import dev.relay.core.RelayApi
import dev.relay.core.RelayJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CallType(val wire: String) { AUDIO("audio"), VIDEO("video") }

@Serializable
data class Call(
    val callId: String, val conversationId: String, val type: String, val status: String,
    // calleeId is null for a group call (there's no single callee — see call_participants
    // instead); isGroup defaults false so this still decodes fine against an old 1:1-only server.
    val callerId: String, val calleeId: String? = null, val isGroup: Boolean = false,
    val startedAt: String? = null, val endedAt: String? = null,
    val endReason: String? = null, val createdAt: String,
)

@Serializable data class TurnCredentials(val urls: List<String>, val username: String, val credential: String, val expiresAt: Long)
@Serializable data class SdpPayload(val type: String, val sdp: String)
@Serializable data class IceCandidatePayload(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

/** One row of the group `/answer` response's `participants` array — external ids, per protocol/events.md. */
@Serializable data class CallParticipant(val userId: String, val joinedAt: String? = null, val leftAt: String? = null, val declinedAt: String? = null)

/** call_* frames, decoded from RelayEvent.Unknown payloads (relay-core only models chat). */
sealed class CallEvent {
    data class Invite(val callId: String, val conversationId: String, val callerId: String, val callerName: String?, val type: CallType, val isGroup: Boolean = false, val participantIds: List<String> = emptyList()) : CallEvent()
    data class Accepted(val callId: String, val acceptedBy: String) : CallEvent()
    data class Declined(val callId: String) : CallEvent()
    data class Ended(val callId: String, val reason: String?) : CallEvent()
    data class Missed(val callId: String) : CallEvent()
    data class Offer(val callId: String, val senderId: String?, val targetUserId: String?, val sdp: SdpPayload) : CallEvent()
    data class AnswerSdp(val callId: String, val senderId: String?, val targetUserId: String?, val sdp: SdpPayload) : CallEvent()
    data class Ice(val callId: String, val senderId: String?, val targetUserId: String?, val candidate: IceCandidatePayload) : CallEvent()
    // Only the field that changed is present — the other is null, not false. See CallCenter's
    // handle() for why each side must be applied independently.
    data class MediaState(val callId: String, val senderId: String?, val cameraEnabled: Boolean?, val micEnabled: Boolean?) : CallEvent()
    data class ParticipantJoined(val callId: String, val conversationId: String, val userId: String, val participantIds: List<String>) : CallEvent()
    data class ParticipantDeclined(val callId: String, val conversationId: String, val userId: String) : CallEvent()
    data class ParticipantLeft(val callId: String, val conversationId: String, val userId: String, val remaining: Int) : CallEvent()

    companion object {
        fun decode(event: String, o: JsonObject): CallEvent? {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun sdp() = o["sdp"]?.jsonObject?.let { SdpPayload(it["type"]?.jsonPrimitive?.contentOrNull ?: return@let null, it["sdp"]?.jsonPrimitive?.contentOrNull ?: return@let null) }
            fun participantIds() = (o["participantIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            return when (event) {
                "call_invite" -> Invite(s("callId") ?: return null, s("conversationId") ?: return null, s("callerId") ?: return null, s("callerName"), if (s("type") == "video") CallType.VIDEO else CallType.AUDIO, o["isGroup"]?.jsonPrimitive?.booleanOrNull ?: false, participantIds())
                "call_accepted" -> Accepted(s("callId") ?: return null, s("acceptedBy") ?: return null)
                "call_declined" -> Declined(s("callId") ?: return null)
                "call_end" -> Ended(s("callId") ?: return null, s("reason"))
                "call_missed" -> Missed(s("callId") ?: return null)
                "call_offer" -> Offer(s("callId") ?: return null, s("senderId"), s("targetUserId"), sdp() ?: return null)
                "call_answer_sdp" -> AnswerSdp(s("callId") ?: return null, s("senderId"), s("targetUserId"), sdp() ?: return null)
                "call_ice_candidate" -> {
                    val c = o["candidate"]?.jsonObject ?: return null
                    Ice(s("callId") ?: return null, s("senderId"), s("targetUserId"), IceCandidatePayload(c["candidate"]?.jsonPrimitive?.contentOrNull ?: return null, c["sdpMid"]?.jsonPrimitive?.contentOrNull, c["sdpMLineIndex"]?.jsonPrimitive?.intOrNull))
                }
                "call_media_state" -> MediaState(s("callId") ?: return null, s("senderId"), o["cameraEnabled"]?.jsonPrimitive?.booleanOrNull, o["micEnabled"]?.jsonPrimitive?.booleanOrNull)
                "call_participant_joined" -> ParticipantJoined(s("callId") ?: return null, s("conversationId") ?: return null, s("userId") ?: return null, participantIds())
                "call_participant_declined" -> ParticipantDeclined(s("callId") ?: return null, s("conversationId") ?: return null, s("userId") ?: return null)
                "call_participant_left" -> ParticipantLeft(s("callId") ?: return null, s("conversationId") ?: return null, s("userId") ?: return null, o["remaining"]?.jsonPrimitive?.intOrNull ?: 0)
                else -> null
            }
        }
    }
}

@Serializable private data class CallEnv(val call: Call)
@Serializable data class AnswerEnv(val call: Call, val participants: List<CallParticipant> = emptyList())
@Serializable private data class StartBody(val conversationId: String, val type: String)
@Serializable private data class EndBody(val reason: String? = null)
@Serializable private data class TurnEnv(val turn: TurnCredentials? = null)

suspend fun RelayApi.startCall(conversationId: String, type: CallType): Call =
    request("POST", "/calls", RelayJson.json.encodeToString(StartBody.serializer(), StartBody(conversationId, type.wire)), CallEnv.serializer()).call
/** The group-call `participants` array — empty for a 1:1 call's answer response. */
suspend fun RelayApi.answerCallWithParticipants(id: String): AnswerEnv = request("POST", "/calls/$id/answer", serializer = AnswerEnv.serializer())
suspend fun RelayApi.answerCall(id: String): Call = answerCallWithParticipants(id).call
suspend fun RelayApi.declineCall(id: String): Call = request("POST", "/calls/$id/decline", serializer = CallEnv.serializer()).call
suspend fun RelayApi.endCall(id: String, reason: String? = null): Call =
    request("POST", "/calls/$id/end", reason?.let { RelayJson.json.encodeToString(EndBody.serializer(), EndBody(it)) }, CallEnv.serializer()).call
suspend fun RelayApi.turnCredentials(): TurnCredentials? = request("GET", "/calls/turn-credentials", serializer = TurnEnv.serializer()).turn
