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
        assertEquals(CallEvent.MediaState("1", false, null), cameraOnly)
        val micOnly = decode("""{"event":"call_media_state","callId":"1","senderId":"alice","micEnabled":false}""") as CallEvent.MediaState
        assertEquals(CallEvent.MediaState("1", null, false), micOnly)
        assertNull(decode("""{"event":"call_media_state"}"""))
    }
}
