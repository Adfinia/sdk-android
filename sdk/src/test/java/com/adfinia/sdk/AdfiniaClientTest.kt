// Smoke tests — only exercise the public surface. Full coverage lands
// with NEXT-AND-2 (real transport tests).

package com.adfinia.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdfiniaClientTest {
    @Test
    fun `identify accepts the customer-id form`() {
        val arg = AdfiniaIdentifyArg.CustomerId("cust_42")
        assertEquals("cust_42", (arg as AdfiniaIdentifyArg.CustomerId).id)
    }

    @Test
    fun `config holds the defaults`() {
        val cfg = AdfiniaConfig(writeKey = "pk_test_x")
        assertEquals("https://events.adfinia.com", cfg.host)
        assertEquals(50, cfg.flushAt)
        assertEquals(5_000L, cfg.flushIntervalMs)
        assertEquals(1_000, cfg.maxQueueSize)
    }

    @Test
    fun `flush on an uninitialised client is a no-op`() = runTest {
        val client = AdfiniaClient()
        client.flush() // should not throw
    }

    @Test
    fun `identify stores an anonymous id`() {
        val identity = IdentityStore()
        assertNotNull(identity.anonymousId)
        identity.identify("cust_42", mapOf("plan" to "growth"), null)
        assertEquals("cust_42", identity.customerId)
        assertEquals("growth", identity.traits?.get("plan"))
    }
}
