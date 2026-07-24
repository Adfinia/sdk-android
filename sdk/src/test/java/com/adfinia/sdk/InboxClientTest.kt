// InboxClientTest — exercises AdfiniaInbox against MockWebServer: the list
// request shape (contact_id + status + cursor + limit), model parsing, the
// mark-read + mark-all-read paths, and contact_id resolution/override.

package com.adfinia.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InboxClientTest {

    private lateinit var server: MockWebServer

    private fun inbox(contactId: String? = "cust_42"): AdfiniaInbox {
        val host = server.url("").toString().trimEnd('/')
        return AdfiniaInbox(
            http = AdfiniaHttp(host = host, writeKey = "pk_test_x"),
            host = host,
            writeKey = "pk_test_x",
            resolveContactId = { contactId },
            debug = {},
        )
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list sends contact_id + status + limit and parses the page`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": [
                    {
                      "id": "n1", "contact_id": "cust_42",
                      "title": "Welcome", "body": "Hello there",
                      "severity": "success", "dismissable": true,
                      "deep_link": "adfinia://home",
                      "data": {"campaign_id": "c9"},
                      "read": false,
                      "created_at": "2026-07-24T10:00:00Z",
                      "read_at": null, "expires_at": null
                    }
                  ],
                  "next_cursor": "cur_2",
                  "has_more": true
                }
                """.trimIndent(),
            ),
        )

        val page = inbox().list(status = InboxStatus.UNREAD, limit = 10)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val url = recorded.requestUrl!!
        assertEquals("/api/v1/notifications", url.encodedPath)
        assertEquals("cust_42", url.queryParameter("contact_id"))
        assertEquals("unread", url.queryParameter("status"))
        assertEquals("10", url.queryParameter("limit"))
        assertEquals("Bearer pk_test_x", recorded.getHeader("Authorization"))

        assertEquals(1, page.data.size)
        assertEquals("cur_2", page.nextCursor)
        assertTrue(page.hasMore)
        val n = page.data[0]
        assertEquals("n1", n.id)
        assertEquals("Welcome", n.title)
        assertEquals(InboxSeverity.SUCCESS, n.severity)
        assertTrue(n.dismissable)
        assertEquals("adfinia://home", n.deepLink)
        assertEquals("c9", n.data["campaign_id"])
        assertFalse(n.read)
        assertNull(n.readAt)
    }

    @Test
    fun `list defaults status to all and clamps limit`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"has_more":false}"""))
        inbox().list(limit = 9999)
        val url = server.takeRequest().requestUrl!!
        assertEquals("all", url.queryParameter("status"))
        assertEquals("100", url.queryParameter("limit"))
    }

    @Test
    fun `markRead POSTs to the read path with contact_id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"n1","read":true}"""))
        val ok = inbox().markRead("n1")
        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val url = recorded.requestUrl!!
        assertEquals("/api/v1/notifications/n1/read", url.encodedPath)
        assertEquals("cust_42", url.queryParameter("contact_id"))
    }

    @Test
    fun `markAllRead returns the updated count`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"updated":7}"""))
        val count = inbox().markAllRead()
        assertEquals(7, count)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/notifications/read-all", url.encodedPath)
        assertEquals("cust_42", url.queryParameter("contact_id"))
    }

    @Test
    fun `explicit contactId overrides the resolved identity`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"has_more":false}"""))
        inbox(contactId = "resolved").list(contactId = "explicit")
        assertEquals("explicit", server.takeRequest().requestUrl!!.queryParameter("contact_id"))
    }

    @Test
    fun `list with no resolvable contact_id throws`() {
        var threw = false
        try {
            runBlocking { inbox(contactId = null).list() }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `list returns an empty page on a server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val page = inbox().list()
        assertTrue(page.data.isEmpty())
        assertFalse(page.hasMore)
    }
}
