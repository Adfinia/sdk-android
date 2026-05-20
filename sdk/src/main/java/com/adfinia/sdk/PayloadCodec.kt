// PayloadCodec — wire encoding for SDK payloads.
//
// Two surfaces:
//   1. toWireJson(payload) — encodes a payload to the JSON body the API
//      expects on `/api/v1/identify` or `/api/v1/track`. This mirrors the
//      Web SDK's `toIdentifyWire` / `toTrackWire`.
//   2. toEnvelope(payload) + fromEnvelope(json) — encodes/decodes the
//      payload for on-disk persistence so we can rehydrate the queue
//      after the process is killed.
//
// org.json is bundled with Android (`android.util.Log` adjacent — see
// https://developer.android.com/reference/org/json/JSONObject). For JVM
// unit tests we pull `org.json:json` as a testImplementation dep so the
// same code path executes off-device.

package com.adfinia.sdk

import org.json.JSONArray
import org.json.JSONObject

internal object PayloadCodec {

    // ---------- Wire format (API ingest) ----------

    fun toIdentifyWire(p: AdfiniaPayload): String = JSONObject().apply {
        if (p.customerId != null) put("customer_id", p.customerId)
        put("anonymous_id", p.anonymousId)
        p.traits?.let { put("traits", toJson(it)) }
        put("context", contextToWire(p))
    }.toString()

    fun toTrackWire(p: AdfiniaPayload): String = JSONObject().apply {
        if (p.customerId != null) put("customer_id", p.customerId)
        put("anonymous_id", p.anonymousId)
        put("event_name", p.event ?: synthesiseName(p))
        val props = mergeProperties(p)
        if (props != null) put("properties", toJson(props))
        put("context", contextToWire(p))
        put("occurred_at", p.sentAt)
    }.toString()

    private fun synthesiseName(p: AdfiniaPayload): String = when (p.type) {
        AdfiniaPayloadType.PAGE -> "\$page_viewed"
        AdfiniaPayloadType.SCREEN -> "\$screen_viewed"
        AdfiniaPayloadType.ALIAS -> "\$alias"
        else -> "\$unknown"
    }

    private fun mergeProperties(p: AdfiniaPayload): Map<String, Any?>? {
        // For alias events, carry the previous_id in properties so the server's
        // identity-graph contract picks it up — same as the Web SDK.
        if (p.type == AdfiniaPayloadType.ALIAS && p.previousId != null) {
            val merged = LinkedHashMap<String, Any?>()
            p.properties?.let { merged.putAll(it) }
            merged["previous_id"] = p.previousId
            return merged
        }
        return p.properties
    }

    private fun contextToWire(p: AdfiniaPayload): JSONObject = JSONObject().apply {
        val c = p.context
        put("library.name", c.libraryName)
        put("library.version", c.libraryVersion)
        put("message_id", p.messageId)
        put("sdk_event_type", p.type.wireValue())
        c.locale?.let { put("locale", it) }
        c.timezone?.let { put("timezone", it) }
        c.osName?.let { put("os.name", it) }
        c.osVersion?.let { put("os.version", it) }
        c.deviceManufacturer?.let { put("device.manufacturer", it) }
        c.deviceModel?.let { put("device.model", it) }
        c.appName?.let { put("app.name", it) }
        c.appVersion?.let { put("app.version", it) }
        c.appBuild?.let { put("app.build", it) }
        c.networkType?.let { put("network.type", it) }
        c.screenWidth?.let { put("screen.width", it.toString()) }
        c.screenHeight?.let { put("screen.height", it.toString()) }
    }

    // ---------- Envelope (on-disk queue) ----------

    fun toEnvelope(p: AdfiniaPayload): JSONObject = JSONObject().apply {
        put("type", p.type.wireValue())
        if (p.event != null) put("event", p.event)
        if (p.customerId != null) put("customer_id", p.customerId)
        put("anonymous_id", p.anonymousId)
        if (p.previousId != null) put("previous_id", p.previousId)
        p.properties?.let { put("properties", toJson(it)) }
        p.traits?.let { put("traits", toJson(it)) }
        put("context", contextEnvelope(p.context))
        put("sent_at", p.sentAt)
        put("message_id", p.messageId)
    }

    fun fromEnvelope(o: JSONObject): AdfiniaPayload? {
        val typeStr = o.optString("type", "")
        val type = AdfiniaPayloadType.fromWire(typeStr) ?: return null
        val context = contextFromEnvelope(o.optJSONObject("context")) ?: return null
        val anon = o.optString("anonymous_id", "").ifBlank { return null }
        return AdfiniaPayload(
            type = type,
            event = o.optString("event").ifBlank { null },
            customerId = o.optString("customer_id").ifBlank { null },
            anonymousId = anon,
            previousId = o.optString("previous_id").ifBlank { null },
            properties = o.optJSONObject("properties")?.let { fromJson(it) },
            traits = o.optJSONObject("traits")?.let { fromJson(it) },
            context = context,
            sentAt = o.optString("sent_at", ""),
            messageId = o.optString("message_id", ""),
        )
    }

    private fun contextEnvelope(c: AdfiniaContext): JSONObject = JSONObject().apply {
        put("library_name", c.libraryName)
        put("library_version", c.libraryVersion)
        c.locale?.let { put("locale", it) }
        c.timezone?.let { put("timezone", it) }
        c.osName?.let { put("os_name", it) }
        c.osVersion?.let { put("os_version", it) }
        c.deviceManufacturer?.let { put("device_manufacturer", it) }
        c.deviceModel?.let { put("device_model", it) }
        c.appName?.let { put("app_name", it) }
        c.appVersion?.let { put("app_version", it) }
        c.appBuild?.let { put("app_build", it) }
        c.networkType?.let { put("network_type", it) }
        c.screenWidth?.let { put("screen_width", it) }
        c.screenHeight?.let { put("screen_height", it) }
    }

    private fun contextFromEnvelope(o: JSONObject?): AdfiniaContext? {
        if (o == null) return null
        return AdfiniaContext(
            libraryName = o.optString("library_name", ""),
            libraryVersion = o.optString("library_version", ""),
            locale = o.optString("locale").ifBlank { null },
            timezone = o.optString("timezone").ifBlank { null },
            osName = o.optString("os_name").ifBlank { null },
            osVersion = o.optString("os_version").ifBlank { null },
            deviceManufacturer = o.optString("device_manufacturer").ifBlank { null },
            deviceModel = o.optString("device_model").ifBlank { null },
            appName = o.optString("app_name").ifBlank { null },
            appVersion = o.optString("app_version").ifBlank { null },
            appBuild = o.optString("app_build").ifBlank { null },
            networkType = o.optString("network_type").ifBlank { null },
            screenWidth = if (o.has("screen_width")) o.optInt("screen_width") else null,
            screenHeight = if (o.has("screen_height")) o.optInt("screen_height") else null,
        )
    }

    // ---------- Bulk queue serialisation ----------

    fun encodeQueue(payloads: List<AdfiniaPayload>): String {
        val arr = JSONArray()
        payloads.forEach { arr.put(toEnvelope(it)) }
        return arr.toString()
    }

    fun decodeQueue(raw: String): List<AdfiniaPayload> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AdfiniaPayload>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                fromEnvelope(item)?.let(out::add)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ---------- Helpers ----------

    @Suppress("UNCHECKED_CAST")
    internal fun toJson(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, when (v) {
                null -> JSONObject.NULL
                is Map<*, *> -> toJson(v as Map<String, Any?>)
                is List<*> -> toJsonArray(v)
                else -> v
            })
        }
        return o
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJsonArray(list: List<*>): JSONArray {
        val a = JSONArray()
        for (v in list) {
            a.put(when (v) {
                null -> JSONObject.NULL
                is Map<*, *> -> toJson(v as Map<String, Any?>)
                is List<*> -> toJsonArray(v)
                else -> v
            })
        }
        return a
    }

    internal fun fromJson(o: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(o.opt(k))
        }
        return out
    }

    private fun fromJsonArray(a: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(a.length())
        for (i in 0 until a.length()) out.add(unwrap(a.opt(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> fromJson(v)
        is JSONArray -> fromJsonArray(v)
        else -> v
    }
}
