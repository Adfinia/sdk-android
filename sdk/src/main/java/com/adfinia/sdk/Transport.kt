// Transport — boundary between the queue and the network. Pluggable for
// tests; the default `OkHttpTransport` runs against the Adfinia ingest API.
//
// Result semantics mirror the Web SDK:
//   - ok=true            → events flushed, drop from queue.
//   - ok=false, permanent → 4xx response, drop with a debug log.
//   - ok=false, !permanent → 5xx / network failure, schedule a backoff retry.

package com.adfinia.sdk

data class TransportResult(
    val ok: Boolean,
    /** Permanent (4xx) failure — caller must drop the events, not retry. */
    val permanent: Boolean,
    val status: Int? = null,
) {
    companion object {
        val OK: TransportResult = TransportResult(true, false)
    }
}

interface AdfiniaTransport {
    suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult
}

/**
 * Wire-ready envelope handed from the queue to the transport. Contains the
 * already-encoded JSON body + the path the request should target. Keeping
 * encoding in the queue layer lets the transport stay payload-shape agnostic
 * and means we don't re-encode on every retry.
 */
data class AdfiniaEnvelope(
    val path: String, // "/api/v1/identify" or "/api/v1/track"
    val body: String, // JSON body
)
