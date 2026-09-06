package dev.relay.core

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    private fun decode(json: String) = RelayEvent.decode(RelayJson.json.parseToJsonElement(json).jsonObject)

    @Test fun `decodes chat_message with clientId`() {
        val e = decode("""{"event":"chat_message","conversationId":"7","clientId":"c1","message":{"id":"9","conversationId":"7","senderId":"alice","body":"hi","createdAt":"2026-09-06T00:00:00Z","deleted":false}}""")
        assertTrue(e is RelayEvent.ChatMessage)
        e as RelayEvent.ChatMessage
        assertEquals("7", e.conversationId); assertEquals("c1", e.clientId); assertEquals("hi", e.message.body)
    }

    @Test fun `presence and members`() {
        val p = decode("""{"event":"presence","userId":"bob","online":false,"lastSeenAt":"2026-09-06T00:00:00Z"}""") as RelayEvent.Presence
        assertEquals(false, p.online)
        val m = decode("""{"event":"members_added","conversationId":"3","userIds":["a","b"]}""") as RelayEvent.MembersAdded
        assertEquals(listOf("a", "b"), m.userIds)
    }

    @Test fun `call frames fall through as Unknown, garbage is null`() {
        val u = decode("""{"event":"call_invite","callId":"1"}""")
        assertTrue(u is RelayEvent.Unknown); assertEquals("call_invite", (u as RelayEvent.Unknown).event)
        assertNull(decode("""{"nope":1}"""))
        assertNull(decode("""{"event":"chat_message","conversationId":"7"}"""))
    }
}
