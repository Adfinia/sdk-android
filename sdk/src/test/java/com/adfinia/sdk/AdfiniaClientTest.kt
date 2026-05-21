// AdfiniaClientTest — mirrors `client.test.ts`. Exercises the coordinator
// end-to-end with an in-memory transport so we don't touch the network.

package com.adfinia.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

private class CapturingTransport(
    @Volatile var result: TransportResult = TransportResult.OK,
) : AdfiniaTransport {
    private val _sent = mutableListOf<AdfiniaEnvelope>()
    override suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult {
        synchronized(_sent) { _sent.addAll(batch) }
        return result
    }
    val sent get() = synchronized(_sent) { _sent.toList() }
}

class AdfiniaClientTest {

    private fun newClient(
        transport: CapturingTransport = CapturingTransport(),
        store: AdfiniaKVStore = AdfiniaMemoryStore(),
    ): Pair<AdfiniaClient, CapturingTransport> {
        val client = AdfiniaClient(AdfiniaHooks(
            store = store,
            transport = transport,
            skipBackgroundWorker = true,
        ))
        return client to transport
    }

    @Test
    fun `throws when writeKey is blank`() {
        val client = AdfiniaClient(AdfiniaHooks(skipBackgroundWorker = true))
        try {
            client.initialize(null, AdfiniaConfig(writeKey = ""))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("writeKey"))
        }
    }

    @Test
    fun `public methods called before initialize are no-ops`() = runBlocking {
        val (client, transport) = newClient()
        client.track("Order Completed")
        delay(50)
        assertEquals(0, transport.sent.size)
    }

    @Test
    fun `track enqueues an event with the right shape`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.track("Order Completed", mapOf("total" to 49.99))
        waitFor { transport.sent.size >= 1 }
        val env = transport.sent.single()
        assertEquals(AdfiniaEnvelopeKind.TRACK, env.kind)
        assertTrue(env.body.contains("\"event_name\":\"Order Completed\""))
        assertTrue(env.body.contains("\"total\":49.99"))
        client._shutdownForTesting()
    }

    @Test
    fun `identify(string) emits an identify event with the customer_id`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.identify(AdfiniaIdentifyArg.CustomerId("cust_42"), mapOf("plan" to "growth"))
        waitFor { transport.sent.size >= 1 }
        val env = transport.sent.single()
        assertEquals(AdfiniaEnvelopeKind.IDENTIFY, env.kind)
        assertTrue(env.body.contains("\"customer_id\":\"cust_42\""))
        assertTrue(env.body.contains("\"plan\":\"growth\""))
        client._shutdownForTesting()
    }

    @Test
    fun `identify(object) accepts the full object form`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.identify(AdfiniaIdentifyArg.Object(customerId = "cust_99", traits = mapOf("tier" to "enterprise")), null)
        waitFor { transport.sent.size >= 1 }
        assertTrue(transport.sent.single().body.contains("\"customer_id\":\"cust_99\""))
        assertTrue(transport.sent.single().body.contains("\"tier\":\"enterprise\""))
        client._shutdownForTesting()
    }

    @Test
    fun `subsequent track carries the customer_id from identify`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 2,
            flushIntervalMs = 60_000L,
        ))
        client.identify(AdfiniaIdentifyArg.CustomerId("cust_42"), null)
        client.track("Order Completed", null)
        waitFor { transport.sent.size >= 2 }
        assertTrue(transport.sent[1].body.contains("\"customer_id\":\"cust_42\""))
        client._shutdownForTesting()
    }

    @Test
    fun `alias emits an alias event with previous_id and updates identity`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.alias("cust_new", "cust_old")
        waitFor { transport.sent.size >= 1 }
        val env = transport.sent.single()
        assertEquals(AdfiniaEnvelopeKind.TRACK, env.kind)
        assertTrue(env.body.contains("\"event_name\":\"\$alias\""))
        assertTrue(env.body.contains("\"previous_id\":\"cust_old\""))
        assertEquals("cust_new", client._identity()?.customerId)
        client._shutdownForTesting()
    }

    @Test
    fun `reset mints a new anonymous_id and clears customer_id`() {
        val (client, _) = newClient()
        client.initialize(null, AdfiniaConfig(writeKey = "pk_test_x"))
        val before = client._identity()?.anonymousId
        client.identify(AdfiniaIdentifyArg.CustomerId("cust_42"), null)
        client.reset()
        assertNull(client._identity()?.customerId)
        assertNotEquals(before, client._identity()?.anonymousId)
        client._shutdownForTesting()
    }

    @Test
    fun `consent gate drops events when consent returns false`() = runBlocking {
        val (client, transport) = newClient()
        val consented = AtomicBoolean(false)
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            consent = AdfiniaConsent { consented.get() },
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.track("Order Completed", null)
        delay(50)
        assertEquals(0, transport.sent.size)

        consented.set(true)
        client.track("Order Completed", null)
        waitFor { transport.sent.size >= 1 }
        assertEquals(1, transport.sent.size)
        client._shutdownForTesting()
    }

    @Test
    fun `consent gate that throws is treated as no-consent (fail-closed)`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            consent = AdfiniaConsent { throw IllegalStateException("banner crashed") },
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.track("Order Completed", null)
        delay(50)
        assertEquals(0, transport.sent.size)
        client._shutdownForTesting()
    }

    @Test
    fun `track without an event name is a no-op`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.track("", null)
        client.track("   ", null)
        delay(50)
        assertEquals(0, transport.sent.size)
        client._shutdownForTesting()
    }

    @Test
    fun `initialize twice keeps the original identity`() {
        val (client, _) = newClient()
        client.initialize(null, AdfiniaConfig(writeKey = "pk_test_x"))
        val id = client._identity()?.anonymousId
        client.initialize(null, AdfiniaConfig(writeKey = "pk_test_y"))
        assertEquals(id, client._identity()?.anonymousId)
        client._shutdownForTesting()
    }

    @Test
    fun `flush triggers transport on demand`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 100,
            flushIntervalMs = 60_000L,
        ))
        client.track("a", null)
        client.track("b", null)
        assertEquals(0, transport.sent.size)
        client.flush()
        assertEquals(2, transport.sent.size)
        client._shutdownForTesting()
    }

    @Test
    fun `screen routes to the track endpoint with synthesised event name`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.screen("Pricing", null)
        waitFor { transport.sent.size >= 1 }
        val env = transport.sent.single()
        assertEquals(AdfiniaEnvelopeKind.TRACK, env.kind)
        assertTrue(env.body.contains("\"event_name\":\"Pricing\""))
        client._shutdownForTesting()
    }

    @Test
    fun `screen with no name synthesises a screen_viewed name`() = runBlocking {
        val (client, transport) = newClient()
        client.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 1,
            flushIntervalMs = 60_000L,
        ))
        client.screen(null, null)
        waitFor { transport.sent.size >= 1 }
        assertTrue(transport.sent.single().body.contains("\"event_name\":\"\$screen_viewed\""))
        client._shutdownForTesting()
    }

    @Test
    fun `identity survives a process restart via the shared store`() {
        val store = AdfiniaMemoryStore()
        val (c1, _) = newClient(store = store)
        c1.initialize(null, AdfiniaConfig(writeKey = "pk_test_x"))
        val anon = c1._identity()?.anonymousId
        c1.identify(AdfiniaIdentifyArg.CustomerId("cust_42"), mapOf("plan" to "growth"))
        c1._shutdownForTesting()

        val (c2, _) = newClient(store = store)
        c2.initialize(null, AdfiniaConfig(writeKey = "pk_test_x"))
        assertEquals(anon, c2._identity()?.anonymousId)
        assertEquals("cust_42", c2._identity()?.customerId)
        assertEquals("growth", c2._identity()?.traits?.get("plan"))
        c2._shutdownForTesting()
    }

    @Test
    fun `queued events survive a process restart`() = runBlocking {
        val store = AdfiniaMemoryStore()
        val deadTransport = CapturingTransport(
            TransportResult(ok = false, permanent = false, status = 503),
        )
        val c1 = AdfiniaClient(AdfiniaHooks(
            store = store,
            transport = deadTransport,
            skipBackgroundWorker = true,
        ))
        c1.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 100,
            flushIntervalMs = 60_000L,
        ))
        c1.track("a", null)
        c1.track("b", null)
        c1.flush() // 503 → events stay buffered.
        c1._shutdownForTesting()

        val okTransport = CapturingTransport()
        val c2 = AdfiniaClient(AdfiniaHooks(
            store = store,
            transport = okTransport,
            skipBackgroundWorker = true,
        ))
        c2.initialize(null, AdfiniaConfig(
            writeKey = "pk_test_x",
            flushAt = 100,
            flushIntervalMs = 60_000L,
        ))
        c2.flush()
        assertEquals(2, okTransport.sent.size)
        c2._shutdownForTesting()
    }

    private suspend fun waitFor(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(10)
        }
    }
}
