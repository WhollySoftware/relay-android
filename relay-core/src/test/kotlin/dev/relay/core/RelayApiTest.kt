package dev.relay.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RelayApiTest {
    private val server = MockWebServer()
    private var tokenCalls = 0
    private lateinit var api: RelayApi

    @Before fun setUp() {
        server.start()
        val tokens = TokenSource { tokenCalls++; "tok-$tokenCalls" }
        api = RelayApi(RelayConfig(server.url("/").toString(), "pk_test", { "unused" }), tokens)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `sends the app package id header only when configured`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user":{"userId":"alice","isOnline":true}}"""))
        api.me()
        assertNull(server.takeRequest().getHeader("X-App-Package-Id"))

        val withPackageId = RelayApi(RelayConfig(server.url("/").toString(), "pk_test", { "unused" }, packageId = "com.example.app"), TokenSource { "tok" })
        server.enqueue(MockResponse().setBody("""{"user":{"userId":"alice","isOnline":true}}"""))
        withPackageId.me()
        assertEquals("com.example.app", server.takeRequest().getHeader("X-App-Package-Id"))
    }

    @Test fun `sends key and bearer, decodes envelope`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user":{"userId":"alice","displayName":"Alice","isOnline":true}}"""))
        val me = api.me()
        assertEquals("alice", me.userId)
        val req = server.takeRequest()
        assertEquals("/users/me", req.path)
        assertEquals("pk_test", req.getHeader("X-Relay-Key"))
        assertEquals("Bearer tok-1", req.getHeader("Authorization"))
    }

    @Test fun `401 refreshes the token once and retries`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","message":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"user":{"userId":"alice","isOnline":false}}"""))
        api.me()
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, tokenCalls)
    }

    @Test fun `second 401 surfaces as RelayException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","message":"nope"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","message":"still nope"}"""))
        try { api.me(); fail("expected RelayException") } catch (e: RelayException) {
            assertEquals(401, e.status); assertEquals("unauthorized", e.code); assertEquals("still nope", e.message)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `device registration posts token type and accepts 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        api.registerDevice("fcm-token-abcdefghijklmnop")
        api.unregisterDevice("fcm-token-abcdefghijklmnop")
        val reg = server.takeRequest()
        assertEquals("POST", reg.method); assertEquals("/devices", reg.path)
        assertEquals("""{"token":"fcm-token-abcdefghijklmnop","tokenType":"fcm"}""", reg.body.readUtf8())
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertTrue(del.body.readUtf8().contains("\"token\":\"fcm-token-abcdefghijklmnop\""))
    }

    @Test fun `server error carries code and message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"busy","message":"One of you is already on a call"}"""))
        try { api.openConversation("bob"); fail() } catch (e: RelayException) {
            assertEquals(409, e.status); assertEquals("busy", e.code)
        }
        assertNull(null)
    }
}
