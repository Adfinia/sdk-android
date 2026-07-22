// AdfiniaFacadeTest — confirms the static `Adfinia.*` surface delegates to
// the client and that `installForTesting()` correctly swaps the instance.

package com.adfinia.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class StubTransport : AdfiniaTransport {
    val sent = mutableListOf<AdfiniaEnvelope>()
    override suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult {
        synchronized(sent) { sent.addAll(batch) }
        return TransportResult.OK
    }
}

class AdfiniaFacadeTest {

    private lateinit var transport: StubTransport

    @Before
    fun setUp() {
        transport = StubTransport()
        val client = AdfiniaClient(AdfiniaHooks(
            store = AdfiniaMemoryStore(),
            transport = transport,
            skipBackgroundWorker = true,
        ))
        Adfinia.installForTesting(client)
    }

    @After
    fun tearDown() {
        Adfinia.installForTesting(AdfiniaClient(AdfiniaHooks(skipBackgroundWorker = true)))
    }

    @Test
    fun `static facade delegates to the configured client`() = runBlocking {
        Adfinia.client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 2,
            flushIntervalMs = 60_000L,
        ))
        Adfinia.identify("cust_42", mapOf("plan" to "growth"))
        Adfinia.track("Order Completed", mapOf("total" to 49.99))
        withTimeout(2_000L) {
            while (synchronized(transport.sent) { transport.sent.size < 2 }) delay(10)
        }
        val sent = synchronized(transport.sent) { transport.sent.toList() }
        assertEquals(2, sent.size)
        assertTrue(sent.any { it.kind == AdfiniaEnvelopeKind.IDENTIFY })
        assertTrue(sent.any { it.kind == AdfiniaEnvelopeKind.TRACK && it.body.contains("Order Completed") })
    }

    @Test
    fun `flush is a no-op before initialize`() = runBlocking {
        // Adfinia.client is the fresh client from setUp() — not yet initialised.
        Adfinia.flush() // must not throw
    }

    @Test
    fun `flushBlocking matches the suspend flush`() {
        Adfinia.client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 100,
            flushIntervalMs = 60_000L,
        ))
        Adfinia.track("a", null)
        Adfinia.track("b", null)
        Adfinia.flushBlocking()
        assertEquals(2, synchronized(transport.sent) { transport.sent.size })
    }

    @Test
    fun `optOut on the static facade delegates and emits a consent_updated event`() = runBlocking {
        Adfinia.client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        Adfinia.optOut(listOf("email", "sms"))
        withTimeout(2_000L) {
            while (synchronized(transport.sent) { transport.sent.isEmpty() }) delay(10)
        }
        val body = synchronized(transport.sent) { transport.sent.single().body }
        assertTrue(body.contains("\"event_name\":\"consent_updated\""))
        assertTrue(body.contains("\"channels\":[\"email\",\"sms\"]"))
        assertTrue(body.contains("\"status\":\"opted_out\""))
    }

    @Test
    fun `reset clears the customer_id surface`() {
        Adfinia.client.initialize(null, AdfiniaConfig(writeKey = "pk_test_x"))
        Adfinia.identify("cust_42")
        assertEquals("cust_42", Adfinia.client._identity()?.customerId)
        Adfinia.reset()
        assertEquals(null, Adfinia.client._identity()?.customerId)
    }
}
