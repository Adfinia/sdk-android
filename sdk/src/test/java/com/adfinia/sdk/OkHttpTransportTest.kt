// OkHttpTransportTest — mirrors `transport.test.ts`. Uses MockWebServer so
// we exercise the real OkHttp stack without leaving the JVM.

package com.adfinia.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        transport = OkHttpTransport(
            host = server.url("").toString().trimEnd('/'),
            writeKey = "pk_test_x",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `POSTs track envelopes to the track path with bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true}"""))
        val res = transport.send(listOf(
            AdfiniaEnvelope("/api/v1/track", """{"event_name":"hi"}"""),
        ))
        assertTrue(res.ok)
        assertFalse(res.permanent)
        assertEquals(202, res.status)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/track", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer pk_test_x", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("hi", body.getString("event_name"))
    }

    @Test
    fun `routes identify envelopes to the identify path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"customer_id":"abc","created":true}"""))
        val res = transport.send(listOf(
            AdfiniaEnvelope("/api/v1/identify", """{"customer_id":"abc"}"""),
        ))
        assertTrue(res.ok)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/identify", recorded.path)
    }

    @Test
    fun `returns permanent=true on 4xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val res = transport.send(listOf(AdfiniaEnvelope("/api/v1/track", "{}")))
        assertFalse(res.ok)
        assertTrue(res.permanent)
        assertEquals(400, res.status)
    }

    @Test
    fun `returns permanent=false on 5xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val res = transport.send(listOf(AdfiniaEnvelope("/api/v1/track", "{}")))
        assertFalse(res.ok)
        assertFalse(res.permanent)
        assertEquals(503, res.status)
    }

    @Test
    fun `worst result wins across a mixed batch`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(503))
        val res = transport.send(listOf(
            AdfiniaEnvelope("/api/v1/track", """{"event_name":"a"}"""),
            AdfiniaEnvelope("/api/v1/track", """{"event_name":"b"}"""),
        ))
        assertFalse(res.ok)
        // Any retryable failure ⇒ permanent=false so the queue retries.
        assertFalse(res.permanent)
    }

    @Test
    fun `permanent across a mixed batch dominates retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(503))
        val res = transport.send(listOf(
            AdfiniaEnvelope("/api/v1/track", """{"event_name":"a"}"""),
            AdfiniaEnvelope("/api/v1/track", """{"event_name":"b"}"""),
        ))
        assertFalse(res.ok)
        assertTrue("any 4xx in the batch should escalate to permanent", res.permanent)
    }

    @Test
    fun `empty batch is a no-op`() = runBlocking {
        val res = transport.send(emptyList())
        assertTrue(res.ok)
        assertEquals(0, server.requestCount)
    }
}
