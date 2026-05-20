// IdentityStoreTest — mirrors `identity.test.ts`.

package com.adfinia.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityStoreTest {

    @Test
    fun `mints an anonymous_id on first construction`() {
        val store = IdentityStore(AdfiniaMemoryStore())
        assertNotNull(store.anonymousId)
        assertTrue(store.anonymousId.matches(Regex("^[0-9a-f-]{36}$")))
        assertNull(store.customerId)
    }

    @Test
    fun `persists anonymous_id across constructions on a shared backing store`() {
        val backing = AdfiniaMemoryStore()
        val s1 = IdentityStore(backing)
        val id1 = s1.anonymousId
        val s2 = IdentityStore(backing)
        assertEquals(id1, s2.anonymousId)
    }

    @Test
    fun `records customer_id and merges traits`() {
        val store = IdentityStore(AdfiniaMemoryStore())
        store.identify("cust_42", mapOf("plan" to "growth"), null)
        assertEquals("cust_42", store.customerId)
        assertEquals("growth", store.traits?.get("plan"))

        store.identify("cust_42", mapOf("country" to "AE"), null)
        assertEquals("growth", store.traits?.get("plan"))
        assertEquals("AE", store.traits?.get("country"))
    }

    @Test
    fun `reset mints a new anonymous_id and clears customer_id`() {
        val store = IdentityStore(AdfiniaMemoryStore())
        val original = store.anonymousId
        store.identify("cust_42", mapOf("plan" to "growth"), null)
        store.reset()
        assertNull(store.customerId)
        assertNull(store.traits)
        assertNotEquals(original, store.anonymousId)
    }

    @Test
    fun `identify(null,null,null) is a no-op`() {
        val store = IdentityStore(AdfiniaMemoryStore())
        val before = store.anonymousId
        store.identify(null, null, null)
        assertEquals(before, store.anonymousId)
        assertNull(store.customerId)
    }

    @Test
    fun `survives corrupt persisted state by minting fresh`() {
        val backing = AdfiniaMemoryStore()
        backing.set(IdentityStore.KEY, "{ not valid json")
        val store = IdentityStore(backing)
        assertNotNull(store.anonymousId)
        assertTrue(store.anonymousId.matches(Regex("^[0-9a-f-]{36}$")))
    }

    @Test
    fun `identify can override the anonymous_id explicitly`() {
        val store = IdentityStore(AdfiniaMemoryStore())
        store.identify(null, null, "anon-overridden")
        assertEquals("anon-overridden", store.anonymousId)
    }
}
