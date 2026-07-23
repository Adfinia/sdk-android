// In-app notification inbox client — the read + mark-read layer over the
// contact-scoped notification cards the backend delivers.
//
// Endpoints (US-MSG-INAPP-INBOX-001):
//   GET  /api/v1/notifications?contact_id=&status=&cursor=&limit=   → list()
//   POST /api/v1/notifications/{id}/read?contact_id=                → markRead()
//   POST /api/v1/notifications/read-all?contact_id=                 → markAllRead()
//   GET  /api/v1/notifications/stream?contact_id=  (SSE)            → stream()
//
// All four are tenant-scoped by the SDK write key (Bearer) plus an explicit
// contact_id. The typed models mirror the backend `InboxNotification` schema.
//
// contact_id resolution: callers may pass an explicit id; otherwise the client
// falls back to the SDK's current identity (customer_id, else anonymous_id).
// NOTE: the backend keys the inbox on the internal contact UUID — pass the id
// your identify() call resolves to when it differs from your customer_id.

package com.adfinia.sdk

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

/** Card severity, used by the host app for styling. */
enum class InboxSeverity {
    INFO, SUCCESS, WARNING, ERROR;

    companion object {
        fun fromWire(s: String?): InboxSeverity = when (s?.lowercase()) {
            "success" -> SUCCESS
            "warning" -> WARNING
            "error" -> ERROR
            else -> INFO
        }
    }
}

/** Read-state filter for list(). */
enum class InboxStatus {
    ALL, UNREAD, READ;

    fun wire(): String = name.lowercase()
}

/** A single in-app notification card. Mirrors the backend InboxNotification. */
data class InboxNotification(
    val id: String,
    val contactId: String,
    val title: String,
    val body: String,
    val severity: InboxSeverity,
    val dismissable: Boolean,
    val deepLink: String?,
    val data: Map<String, Any?>,
    val read: Boolean,
    val createdAt: String,
    val readAt: String?,
    val expiresAt: String?,
) {
    companion object {
        internal fun fromJson(o: JSONObject): InboxNotification = InboxNotification(
            id = o.optString("id"),
            contactId = o.optString("contact_id"),
            title = o.optString("title"),
            body = o.optString("body"),
            severity = InboxSeverity.fromWire(o.optString("severity").ifBlank { null }),
            dismissable = o.optBoolean("dismissable", false),
            deepLink = o.optString("deep_link").ifBlank { null },
            data = o.optJSONObject("data")?.let { PayloadCodec.fromJson(it) } ?: emptyMap(),
            read = o.optBoolean("read", false),
            createdAt = o.optString("created_at"),
            readAt = if (o.isNull("read_at")) null else o.optString("read_at").ifBlank { null },
            expiresAt = if (o.isNull("expires_at")) null else o.optString("expires_at").ifBlank { null },
        )
    }
}

/** One page of the inbox. */
data class InboxPage(
    val data: List<InboxNotification>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** Live-stream callbacks. All fire on an OkHttp dispatcher thread. */
interface InboxStreamListener {
    fun onNotification(notification: InboxNotification)
    fun onOpen() {}
    fun onClosed() {}
    fun onError(error: Throwable?) {}
}

class AdfiniaInbox internal constructor(
    private val http: AdfiniaHttp,
    private val host: String,
    private val writeKey: String,
    private val resolveContactId: () -> String?,
    private val debug: (String) -> Unit,
    private val sseClient: OkHttpClient = OkHttpTransport.SHARED_CLIENT,
) {

    /**
     * List inbox cards newest-first. [status] filters read-state; [cursor] +
     * [limit] paginate. Pass [contactId] to override the resolved identity.
     * Throws IllegalStateException when no contact id can be resolved.
     */
    suspend fun list(
        status: InboxStatus = InboxStatus.ALL,
        cursor: String? = null,
        limit: Int = 20,
        contactId: String? = null,
    ): InboxPage {
        val cid = requireContactId(contactId)
        val res = http.get(
            "/api/v1/notifications",
            mapOf(
                "contact_id" to cid,
                "status" to status.wire(),
                "cursor" to cursor,
                "limit" to limit.coerceIn(1, 100).toString(),
            ),
        )
        if (!res.ok || res.body == null) {
            debug("inbox: list failed status=${res.code}")
            return InboxPage(emptyList(), null, false)
        }
        return parsePage(res.body)
    }

    /** Mark one card read. Idempotent server-side. Returns true on 2xx. */
    suspend fun markRead(id: String, contactId: String? = null): Boolean {
        val cid = requireContactId(contactId)
        val res = http.post(
            "/api/v1/notifications/${java.net.URLEncoder.encode(id, "UTF-8")}/read?contact_id=" +
                java.net.URLEncoder.encode(cid, "UTF-8"),
            "{}",
        )
        if (!res.ok) debug("inbox: markRead failed status=${res.code}")
        return res.ok
    }

    /** Mark every unread card read. Returns the number of rows flipped. */
    suspend fun markAllRead(contactId: String? = null): Int {
        val cid = requireContactId(contactId)
        val res = http.post(
            "/api/v1/notifications/read-all?contact_id=" + java.net.URLEncoder.encode(cid, "UTF-8"),
            "{}",
        )
        if (!res.ok || res.body == null) {
            debug("inbox: markAllRead failed status=${res.code}")
            return 0
        }
        return try {
            JSONObject(res.body).optInt("updated", 0)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Open a live SSE stream of new cards. Each server `event: notification`
     * frame is parsed into an [InboxNotification] and delivered to [listener].
     * Returns the [EventSource]; call `cancel()` to close it.
     */
    fun stream(listener: InboxStreamListener, contactId: String? = null): EventSource {
        val cid = requireContactId(contactId)
        val url = (host.trimEnd('/') + "/api/v1/notifications/stream").toHttpUrl()
            .newBuilder()
            .addQueryParameter("contact_id", cid)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("Accept", "text/event-stream")
            .header("X-Adfinia-SDK-Version", BuildMeta.SDK_VERSION_HEADER)
            .build()
        return EventSources.createFactory(sseClient).newEventSource(
            req,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: okhttp3.Response) = listener.onOpen()
                override fun onClosed(eventSource: EventSource) = listener.onClosed()
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    debug("inbox: stream failure ${t?.message}")
                    listener.onError(t)
                }
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    // The backend emits `event: notification` with a JSON body.
                    if (type != null && type != "notification") return
                    try {
                        listener.onNotification(InboxNotification.fromJson(JSONObject(data)))
                    } catch (t: Throwable) {
                        debug("inbox: stream parse error ${t.message}")
                    }
                }
            },
        )
    }

    private fun requireContactId(explicit: String?): String {
        val cid = explicit?.ifBlank { null } ?: resolveContactId()?.ifBlank { null }
        return cid ?: throw IllegalStateException(
            "Adfinia inbox: no contact_id — call identify() first or pass contactId explicitly",
        )
    }

    private fun parsePage(body: String): InboxPage = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data")
        val out = ArrayList<InboxNotification>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(InboxNotification.fromJson(it)) }
            }
        }
        InboxPage(
            data = out,
            nextCursor = root.optString("next_cursor").ifBlank { null },
            hasMore = root.optBoolean("has_more", false),
        )
    } catch (t: Throwable) {
        debug("inbox: page parse error ${t.message}")
        InboxPage(emptyList(), null, false)
    }
}
