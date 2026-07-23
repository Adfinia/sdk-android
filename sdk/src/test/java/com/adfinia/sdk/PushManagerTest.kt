// PushManagerTest — mirrors the RN `pushNative` behaviour. Exercises the real
// OkHttp stack (via MockWebServer + AdfiniaHttp) so the exact /push/register
// wire shape is asserted off-device, plus the unregister + onNewToken paths.

package com.adfinia.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: AdfiniaMemoryStore
    private val tracked = mutableListOf<Pair<String, AdfiniaProperties?>>()

    private fun manager(
        identity: PushIdentity = PushIdentity(customerId = "cust_42", externalId = null, anonymousId = "anon_v7"),
        appVersion: String? = "3.4.1",
        tokenProvider: PushTokenProvider = PushTokenProvider { "fcm_default_token" },
    ): PushManager {
        val http = AdfiniaHttp(host = server.url("").toString().trimEnd('/'), writeKey = "pk_test_x")
        return PushManager(
            http = http,
            store = store,
            identity = { identity },
            appVersion = { appVersion },
            defaultTokenProvider = tokenProvider,
            track = { e, p -> tracked.add(e to p) },
            debug = {},
        )
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        store = AdfiniaMemoryStore()
        tracked.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- buildRegisterBody (pure, no network) ----------

    @Test
    fun `register body matches the RN wire contract`() {
        val body = PushManager.buildRegisterBody(
            token = "tok_abc",
            id = PushIdentity(customerId = "cust_42", externalId = "ext_9", anonymousId = "anon_v7"),
            appVersion = "3.4.1",
        )
        val o = JSONObject(body)
        assertEquals("tok_abc", o.getString("token"))
        assertEquals("android", o.getString("platform"))
        // device_id doubles as the anonymous_id, exactly like RN.
        assertEquals("anon_v7", o.getString("device_id"))
        assertEquals("3.4.1", o.getString("app_version"))
        assertEquals("cust_42", o.getString("customer_id"))
        assertEquals("ext_9", o.getString("external_id"))
        assertEquals("anon_v7", o.getString("anonymous_id"))
    }

    @Test
    fun `register body omits absent optional identity fields`() {
        val body = PushManager.buildRegisterBody(
            token = "tok_abc",
            id = PushIdentity(customerId = null, externalId = null, anonymousId = "anon_v7"),
            appVersion = null,
        )
        val o = JSONObject(body)
        assertFalse(o.has("customer_id"))
        assertFalse(o.has("external_id"))
        assertFalse(o.has("app_version"))
        assertEquals("anon_v7", o.getString("anonymous_id"))
    }

    // ---------- register() over the wire ----------

    @Test
    fun `register POSTs the token to push-register with bearer auth and emits push_registered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"token":"t","platform":"android","registered_at":"2026-07-24T00:00:00Z"}"""))
        val res = manager().register(null)

        assertTrue(res is RegisterPushResult.Success)
        assertEquals("fcm_default_token", (res as RegisterPushResult.Success).token)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/push/register", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer pk_test_x", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("fcm_default_token", body.getString("token"))
        assertEquals("android", body.getString("platform"))
        assertEquals("anon_v7", body.getString("device_id"))
        assertEquals("cust_42", body.getString("customer_id"))

        // push_registered emitted + token cached for later unregister/dedupe.
        assertEquals(1, tracked.size)
        assertEquals("push_registered", tracked[0].first)
        assertEquals("android", tracked[0].second?.get("platform"))
        assertEquals("fcm_default_token", store.get(PushManager.KEY_TOKEN))
    }

    @Test
    fun `caller-supplied token wins over the default provider`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        manager(tokenProvider = { "should_not_be_used" }).register("caller_token")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("caller_token", body.getString("token"))
    }

    @Test
    fun `null token from provider yields TOKEN_FAILED and no request`() = runBlocking {
        val res = manager(tokenProvider = { null }).register(null)
        assertTrue(res is RegisterPushResult.Failure)
        assertEquals(RegisterPushFailureReason.TOKEN_FAILED, (res as RegisterPushResult.Failure).reason)
        assertEquals(0, server.requestCount)
        assertTrue(tracked.isEmpty())
    }

    @Test
    fun `server rejection yields POST_FAILED and does not emit or cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val res = manager().register("tok")
        assertTrue(res is RegisterPushResult.Failure)
        assertEquals(RegisterPushFailureReason.POST_FAILED, (res as RegisterPushResult.Failure).reason)
        assertTrue(tracked.isEmpty())
        assertNull(store.get(PushManager.KEY_TOKEN))
    }

    // ---------- onNewToken() dedupe ----------

    @Test
    fun `onNewToken re-registers a changed token and skips an unchanged one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        manager().register("tok_1")
        server.takeRequest()

        // Unchanged → no new request.
        val same = manager().onNewToken("tok_1")
        assertTrue(same is RegisterPushResult.Success)
        assertEquals(1, server.requestCount)

        // Changed → one more POST with the new token.
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        manager().onNewToken("tok_2")
        assertEquals(2, server.requestCount)
        assertEquals("tok_2", store.get(PushManager.KEY_TOKEN))
    }

    // ---------- unregister() ----------

    @Test
    fun `unregister DELETEs the cached token and clears it`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        val m = manager()
        m.register("tok_del")
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(204))
        val res = m.unregister()
        assertTrue(res is RegisterPushResult.Success)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(recorded.path!!.startsWith("/api/v1/push/register/tok_del"))
        assertNull(store.get(PushManager.KEY_TOKEN))
        assertTrue(tracked.any { it.first == "push_unregistered" })
    }

    @Test
    fun `unregister with no cached token is a no-op failure`() = runBlocking {
        val res = manager().unregister()
        assertTrue(res is RegisterPushResult.Failure)
        assertEquals(0, server.requestCount)
    }
}
