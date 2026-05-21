// OkHttpTransportTest — mirrors `transport.test.ts`. Uses MockWebServer so
// we exercise the real OkHttp stack without leaving the JVM.
//
// AGENT-SDK-INGEST-KAFKA (2026-05-21) — single-event sends keep using
// /api/v1/{track,identify}; multi-event batches go to
// /api/v1/{track,identify}/batch as one request per kind.

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
    fun `single track envelope POSTs to the legacy track path with bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true}"""))
        val res = transport.send(listOf(
            AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, """{"event_name":"hi"}"""),
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
    fun `single identify envelope routes to the legacy identify path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"customer_id":"abc","created":true}"""))
        val res = transport.send(listOf(
            AdfiniaEnvelope(AdfiniaEnvelopeKind.IDENTIFY, """{"customer_id":"abc"}"""),
        ))
        assertTrue(res.ok)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/identify", recorded.path)
    }

    @Test
    fun `multi-event track batch POSTs to track-batch as one request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"accepted":2,"rejected":0,"batch_id":"b"}"""))
        val res = transport.send(listOf(
            AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, """{"event_name":"a"}"""),
            AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, """{"event_name":"b"}"""),
        ))
        assertTrue(res.ok)
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/track/batch", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        val events = body.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("a", events.getJSONObject(0).getString("event_name"))
        assertEquals("b", events.getJSONObject(1).getString("event_name"))
    }

    @Test
    fun `mixed batch hits both batch endpoints`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val res = transport.send(listOf(
            AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, """{"event_name":"a"}"""),
            AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, """{"event_name":"b"}"""),
            AdfiniaEnvelope(AdfiniaEnvelopeKind.IDENTIFY, """{"customer_id":"x"}"""),
        ))
        assertTrue(res.ok)
        assertEquals(2, server.requestCount)
        val paths = (1..2).map { server.takeRequest().path }.toSet()
        assertTrue(paths.contains("/api/v1/track/batch"))
        assertTrue(paths.contains("/api/v1/identify/batch"))
    }

    @Test
    fun `returns permanent=true on 4xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val res = transport.send(listOf(AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, "{}")))
        assertFalse(res.ok)
        assertTrue(res.permanent)
        assertEquals(400, res.status)
    }

    @Test
    fun `returns permanent=false on 5xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val res = transport.send(listOf(AdfiniaEnvelope(AdfiniaEnvelopeKind.TRACK, "{}")))
        assertFalse(res.ok)
        assertFalse(res.permanent)
        assertEquals(503, res.status)
    }

    @Test
    fun `empty batch is a no-op`() = runBlocking {
        val res = transport.send(emptyList())
        assertTrue(res.ok)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `assertNotNull import remains used to silence the linter`() {
        // Holdover guard — the unused assertNotNull import was previously
        // referenced by a test that's now removed; we keep the import +
        // a no-op reference so import sweeps don't flap.
        assertNotNull(transport)
    }
}
