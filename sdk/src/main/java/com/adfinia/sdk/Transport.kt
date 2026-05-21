// Transport — boundary between the queue and the network. Pluggable for
// tests; the default `OkHttpTransport` runs against the Adfinia ingest API.
//
// Result semantics mirror the Web SDK:
//   - ok=true            → events flushed, drop from queue.
//   - ok=false, permanent → 4xx response, drop with a debug log.
//   - ok=false, !permanent → 5xx / network failure, schedule a backoff retry.
//
// AGENT-SDK-INGEST-KAFKA (2026-05-21) — the AdfiniaEnvelope shape adds a
// `kind` discriminator so the transport can group the batch by kind and
// hit the new `/api/v1/{track,identify}/batch` endpoints with one request
// per kind instead of one per event.

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
 * Kind discriminator for an [AdfiniaEnvelope]. The transport groups by
 * kind so it can send a single batch request per kind to the
 * `/api/v1/{track,identify}/batch` endpoints.
 */
enum class AdfiniaEnvelopeKind {
    TRACK,
    IDENTIFY,
}

/**
 * Wire-ready envelope handed from the queue to the transport. Contains the
 * already-encoded JSON body + the kind discriminator so the transport can
 * group by `/track` vs `/identify`. Keeping encoding in the queue layer
 * lets the transport stay payload-shape agnostic and means we don't
 * re-encode on every retry.
 */
data class AdfiniaEnvelope(
    val kind: AdfiniaEnvelopeKind,
    val body: String, // JSON body for a single event
)
