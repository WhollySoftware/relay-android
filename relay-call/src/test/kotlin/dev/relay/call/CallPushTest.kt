package dev.relay.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPushTest {
    @Test fun `parses an invite`() {
        val p = CallPush.parse(mapOf("relay" to "call_invite", "callId" to "42", "conversationId" to "7", "callerId" to "alice", "callerName" to "Alice", "type" to "video", "createdAt" to "x"))
        assertEquals(CallPush.Invite("42", "7", "alice", "Alice", CallType.VIDEO), p)
    }

    @Test fun `blank caller name becomes null, unknown type is audio`() {
        val p = CallPush.parse(mapOf("relay" to "call_invite", "callId" to "42", "conversationId" to "7", "callerId" to "alice", "callerName" to "", "type" to "hologram")) as CallPush.Invite
        assertNull(p.callerName); assertEquals(CallType.AUDIO, p.type)
    }

    @Test fun `cancel and non-relay pushes`() {
        assertEquals(CallPush.Cancel("42", "timeout"), CallPush.parse(mapOf("relay" to "call_cancel", "callId" to "42", "reason" to "timeout")))
        assertNull(CallPush.parse(mapOf("relay" to "call_cancel")))
        assertNull(CallPush.parse(mapOf("relay" to "call_invite", "callId" to "42")))
        assertNull(CallPush.parse(mapOf("title" to "hello")))
        assertFalse(CallPush.isRelayPush(mapOf("relay" to "chat_message")))
        assertTrue(CallPush.isRelayPush(mapOf("relay" to "call_invite")))
    }
}
