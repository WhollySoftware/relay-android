package dev.relay.call

import dev.relay.core.RelayJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallEventTest {
    private fun decode(json: String): CallEvent? {
        val o = RelayJson.json.parseToJsonElement(json).jsonObject
        return CallEvent.decode(o["event"]!!.toString().trim('"'), o)
    }

    @Test fun `lifecycle events`() {
        assertEquals(CallEvent.Invite("1", "7", "alice", "Alice", CallType.VIDEO),
            decode("""{"event":"call_invite","callId":"1","conversationId":"7","callerId":"alice","callerName":"Alice","calleeId":"bob","type":"video"}"""))
        assertEquals(CallEvent.Accepted("1", "bob"), decode("""{"event":"call_accepted","callId":"1","conversationId":"7","acceptedBy":"bob"}"""))
        assertEquals(CallEvent.Declined("1"), decode("""{"event":"call_declined","callId":"1","declinedBy":"bob"}"""))
        assertEquals(CallEvent.Ended("1", "hangup"), decode("""{"event":"call_end","callId":"1","endedBy":"alice","durationSec":3,"reason":"hangup"}"""))
        assertEquals(CallEvent.Missed("1"), decode("""{"event":"call_missed","callId":"1"}"""))
    }

    @Test fun `signaling events`() {
        val offer = decode("""{"event":"call_offer","callId":"1","senderId":"alice","sdp":{"type":"offer","sdp":"v=0"}}""") as CallEvent.Offer
        assertEquals(SdpPayload("offer", "v=0"), offer.sdp)
        val ice = decode("""{"event":"call_ice_candidate","callId":"1","senderId":"alice","candidate":{"candidate":"c","sdpMid":"0","sdpMLineIndex":0}}""") as CallEvent.Ice
        assertEquals(IceCandidatePayload("c", "0", 0), ice.candidate)
        assertNull(decode("""{"event":"call_offer","callId":"1"}"""))
        assertNull(decode("""{"event":"chat_message","conversationId":"1"}"""))
    }

    @Test fun `media state — only the changed field is present`() {
        val cameraOnly = decode("""{"event":"call_media_state","callId":"1","senderId":"alice","cameraEnabled":false}""") as CallEvent.MediaState
        assertEquals(CallEvent.MediaState("1", "alice", false, null), cameraOnly)
        val micOnly = decode("""{"event":"call_media_state","callId":"1","senderId":"alice","micEnabled":false}""") as CallEvent.MediaState
        assertEquals(CallEvent.MediaState("1", "alice", null, false), micOnly)
        assertNull(decode("""{"event":"call_media_state"}"""))
    }

    @Test fun `group call events`() {
        val invite = decode("""{"event":"call_invite","callId":"1","conversationId":"7","callerId":"alice","callerName":"Alice","isGroup":true,"participantIds":["alice","bob","carol"],"type":"video"}""") as CallEvent.Invite
        assertEquals(CallEvent.Invite("1", "7", "alice", "Alice", CallType.VIDEO, true, listOf("alice", "bob", "carol")), invite)

        val offer = decode("""{"event":"call_offer","callId":"1","senderId":"alice","targetUserId":"bob","sdp":{"type":"offer","sdp":"v=0"}}""") as CallEvent.Offer
        assertEquals("bob", offer.targetUserId); assertEquals("alice", offer.senderId)

        val joined = decode("""{"event":"call_participant_joined","callId":"1","conversationId":"7","userId":"bob","participantIds":["alice","bob"]}""") as CallEvent.ParticipantJoined
        assertEquals(CallEvent.ParticipantJoined("1", "7", "bob", listOf("alice", "bob")), joined)

        val declined = decode("""{"event":"call_participant_declined","callId":"1","conversationId":"7","userId":"carol"}""") as CallEvent.ParticipantDeclined
        assertEquals(CallEvent.ParticipantDeclined("1", "7", "carol"), declined)

        val left = decode("""{"event":"call_participant_left","callId":"1","conversationId":"7","userId":"bob","remaining":2}""") as CallEvent.ParticipantLeft
        assertEquals(CallEvent.ParticipantLeft("1", "7", "bob", 2), left)
    }
}
