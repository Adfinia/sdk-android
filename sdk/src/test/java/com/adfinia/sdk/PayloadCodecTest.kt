// PayloadCodecTest — wire encoding correctness + envelope round-trip.

package com.adfinia.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCodecTest {

    private fun ctx() = AdfiniaContext(
        libraryName = "adfinia-sdk-android",
        libraryVersion = "test",
        locale = "en-GB",
        timezone = "UTC",
        osName = "Android",
        osVersion = "14",
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8",
        appName = "Demo",
        appVersion = "1.2.3",
        appBuild = "42",
    )

    private fun track(): AdfiniaPayload = AdfiniaPayload(
        type = AdfiniaPayloadType.TRACK,
        event = "Order Completed",
        customerId = "cust_42",
        anonymousId = "anon-1",
        previousId = null,
        properties = mapOf("total" to 49.99, "currency" to "AED"),
        traits = null,
        context = ctx(),
        sentAt = "2026-05-19T10:00:00.000Z",
        messageId = "msg-1",
    )

    @Test
    fun `track wire shape matches the API contract`() {
        val body = PayloadCodec.toTrackWire(track())
        val o = JSONObject(body)
        assertEquals("cust_42", o.getString("customer_id"))
        assertEquals("anon-1", o.getString("anonymous_id"))
        assertEquals("Order Completed", o.getString("event_name"))
        assertEquals("2026-05-19T10:00:00.000Z", o.getString("occurred_at"))
        val props = o.getJSONObject("properties")
        assertEquals(49.99, props.getDouble("total"), 0.0001)
        assertEquals("AED", props.getString("currency"))
        val ctx = o.getJSONObject("context")
        assertEquals("adfinia-sdk-android", ctx.getString("library.name"))
        assertEquals("msg-1", ctx.getString("message_id"))
        assertEquals("track", ctx.getString("sdk_event_type"))
        assertEquals("Android", ctx.getString("os.name"))
    }

    @Test
    fun `identify wire shape carries customer_id + traits`() {
        val identify = track().copy(
            type = AdfiniaPayloadType.IDENTIFY,
            event = null,
            traits = mapOf("plan" to "growth"),
            properties = null,
        )
        val o = JSONObject(PayloadCodec.toIdentifyWire(identify))
        assertEquals("cust_42", o.getString("customer_id"))
        assertEquals("growth", o.getJSONObject("traits").getString("plan"))
        assertFalse("identify wire must not include event_name", o.has("event_name"))
    }

    @Test
    fun `alias type is gone so a persisted alias envelope decodes to null`() {
        // ALIAS was removed in 1.1.0 (alias() is a deprecated no-op). fromWire
        // no longer maps "alias", and a persisted alias envelope from an older
        // build is dropped rather than replayed.
        assertNull(AdfiniaPayloadType.fromWire("alias"))
        val legacyAliasEnvelope = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "alias")
                put("anonymous_id", "anon-1")
                put("previous_id", "cust_old")
                put("customer_id", "cust_new")
                put("context", JSONObject().apply {
                    put("library_name", "adfinia-sdk-android")
                    put("library_version", "test")
                })
                put("sent_at", "2026-05-19T10:00:00.000Z")
                put("message_id", "msg-1")
            })
        }.toString()
        assertTrue(
            "legacy alias envelopes must not rehydrate",
            PayloadCodec.decodeQueue(legacyAliasEnvelope).isEmpty(),
        )
    }

    @Test
    fun `page and screen synthesise an event name`() {
        val page = track().copy(type = AdfiniaPayloadType.PAGE, event = null)
        val screen = track().copy(type = AdfiniaPayloadType.SCREEN, event = null)
        assertEquals("\$page_viewed", JSONObject(PayloadCodec.toTrackWire(page)).getString("event_name"))
        assertEquals("\$screen_viewed", JSONObject(PayloadCodec.toTrackWire(screen)).getString("event_name"))
    }

    @Test
    fun `queue envelopes round-trip through JSON`() {
        val payloads = listOf(
            track(),
            track().copy(event = "b", properties = mapOf("k" to "v")),
            track().copy(
                type = AdfiniaPayloadType.IDENTIFY,
                event = null,
                traits = mapOf("plan" to "growth"),
            ),
        )
        val encoded = PayloadCodec.encodeQueue(payloads)
        val decoded = PayloadCodec.decodeQueue(encoded)
        assertEquals(payloads.size, decoded.size)
        for ((i, p) in payloads.withIndex()) {
            assertEquals(p.type, decoded[i].type)
            assertEquals(p.event, decoded[i].event)
            assertEquals(p.customerId, decoded[i].customerId)
            assertEquals(p.anonymousId, decoded[i].anonymousId)
            assertEquals(p.messageId, decoded[i].messageId)
            assertEquals(p.sentAt, decoded[i].sentAt)
            assertEquals(p.properties, decoded[i].properties)
            assertEquals(p.traits, decoded[i].traits)
            assertEquals(p.context.libraryName, decoded[i].context.libraryName)
            assertEquals(p.context.osName, decoded[i].context.osName)
        }
    }

    @Test
    fun `decodeQueue ignores corrupt rows but keeps valid ones`() {
        val one = track()
        val arr = JSONArray()
        arr.put(PayloadCodec.toEnvelope(one))
        arr.put("not an object")
        arr.put(JSONObject().put("type", "garbage"))
        val decoded = PayloadCodec.decodeQueue(arr.toString())
        assertEquals(1, decoded.size)
        assertEquals("Order Completed", decoded.single().event)
    }

    @Test
    fun `decodeQueue handles a blank or invalid blob`() {
        assertTrue(PayloadCodec.decodeQueue("").isEmpty())
        assertTrue(PayloadCodec.decodeQueue("not json").isEmpty())
    }

    @Test
    fun `nested property maps survive the codec`() {
        val nested = track().copy(properties = mapOf(
            "items" to listOf(mapOf("id" to "p1", "qty" to 2), mapOf("id" to "p2", "qty" to 1)),
            "meta" to mapOf("source" to "web", "campaign" to mapOf("id" to "c1")),
        ))
        val decoded = PayloadCodec.decodeQueue(PayloadCodec.encodeQueue(listOf(nested))).single()
        val items = decoded.properties?.get("items") as? List<*>
        assertNotNull(items)
        assertEquals(2, items!!.size)
        @Suppress("UNCHECKED_CAST")
        val first = items[0] as Map<String, Any?>
        assertEquals("p1", first["id"])
    }
}
