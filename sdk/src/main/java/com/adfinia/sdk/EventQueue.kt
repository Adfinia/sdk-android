// EventQueue — persistent buffer of pending events, with batching, drop on
// 4xx, and exponential backoff on 5xx / network failure.
//
// Mirrors the Web SDK's `queue.ts` but adapted for coroutines:
//   - enqueue() is non-suspending; it persists synchronously to the KVStore
//     so a crash immediately after `track()` doesn't lose the event.
//   - flush() is a suspend function (matches the public API surface). It
//     resolves once the in-flight batch resolves — success / permanent
//     drop / transient retry all return.
//   - The internal scheduler kicks off a background coroutine that flushes
//     every `flushIntervalMs` (default 5s) and every time `flushAt` events
//     (default 50) buffer up.
//   - Retry uses exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 30s,
//     reset on next success. Mirrors `queue.ts:retryDelayMs`.

package com.adfinia.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

internal class EventQueue(
    private val store: AdfiniaKVStore,
    private val transport: AdfiniaTransport,
    private val flushAt: Int,
    private val flushIntervalMs: Long,
    private val maxQueueSize: Int,
    private val debug: (String) -> Unit,
    private val context: CoroutineContext = Dispatchers.IO,
    /** Test seam — lets tests inject a controlled time source. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val buffer = ArrayDeque<AdfiniaPayload>()
    private val mutex = Mutex()
    private var retryDelayMs: Long = 0L
    private var inflight = false
    private var destroyed = false

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + context)
    private var timerJob: Job? = null

    init {
        // Rehydrate any events that survived a previous run.
        val raw = store.get(KEY)
        if (raw != null) {
            buffer.addAll(PayloadCodec.decodeQueue(raw))
        }
        scheduleNext()
    }

    /**
     * Enqueue an event. Persists synchronously, then triggers a flush if
     * the batch threshold is hit.
     */
    fun enqueue(payload: AdfiniaPayload) {
        if (destroyed) return
        var sizeAfter: Int
        synchronized(buffer) {
            buffer.addLast(payload)
            // Cap the queue — oldest events are dropped if we overflow.
            while (buffer.size > maxQueueSize) {
                buffer.removeFirst()
            }
            sizeAfter = buffer.size
            persistLocked()
        }
        if (sizeAfter >= flushAt) {
            // Don't block the caller — fire-and-forget.
            scope.launch { flush() }
        }
    }

    /**
     * Flush up to `flushAt` events. Returns when the in-flight batch
     * resolves. Idempotent and safe to call from any thread.
     */
    suspend fun flush() {
        if (destroyed) return
        mutex.withLock {
            if (inflight) return
            if (buffer.isEmpty()) {
                // Nothing to flush — keep the periodic timer alive so we
                // re-check on the next interval.
                scheduleNext()
                return
            }
            inflight = true
        }
        try {
            val sending: List<AdfiniaPayload>
            synchronized(buffer) {
                val n = minOf(buffer.size, flushAt)
                sending = ArrayList(buffer.subList(0, n))
            }
            val envelopes = sending.map { p ->
                if (p.type == AdfiniaPayloadType.IDENTIFY) {
                    AdfiniaEnvelope("/api/v1/identify", PayloadCodec.toIdentifyWire(p))
                } else {
                    AdfiniaEnvelope("/api/v1/track", PayloadCodec.toTrackWire(p))
                }
            }
            val result = try {
                transport.send(envelopes)
            } catch (_: Throwable) {
                TransportResult(ok = false, permanent = false)
            }
            when {
                result.ok -> {
                    synchronized(buffer) {
                        repeat(sending.size) { if (buffer.isNotEmpty()) buffer.removeFirst() }
                        persistLocked()
                    }
                    retryDelayMs = 0L
                    debug("flushed ${sending.size} event(s)")
                }
                result.permanent -> {
                    synchronized(buffer) {
                        repeat(sending.size) { if (buffer.isNotEmpty()) buffer.removeFirst() }
                        persistLocked()
                    }
                    debug("dropped ${sending.size} event(s) on permanent failure status=${result.status ?: "n/a"}")
                }
                else -> {
                    retryDelayMs = if (retryDelayMs == 0L) 1_000L else minOf(retryDelayMs * 2, 30_000L)
                    debug("retrying in ${retryDelayMs}ms (status=${result.status ?: "network"})")
                }
            }
        } finally {
            mutex.withLock { inflight = false }
            scheduleNext()
        }
    }

    /**
     * Drain the in-memory buffer without sending. Returned events are no
     * longer tracked by the queue. Used by the test surface + by anyone
     * who needs to inspect the pending batch.
     */
    fun drainAll(): List<AdfiniaPayload> {
        synchronized(buffer) {
            val out = buffer.toList()
            buffer.clear()
            persistLocked()
            return out
        }
    }

    fun size(): Int = synchronized(buffer) { buffer.size }

    fun destroy() {
        destroyed = true
        timerJob?.cancel()
        timerJob = null
        scope.cancel()
    }

    // ---------- internal ----------

    private fun scheduleNext() {
        if (destroyed) return
        timerJob?.cancel()
        val delayMs = if (retryDelayMs > 0L) retryDelayMs else flushIntervalMs
        timerJob = scope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            flush()
        }
    }

    private fun persistLocked() {
        if (buffer.isEmpty()) {
            store.remove(KEY)
        } else {
            store.set(KEY, PayloadCodec.encodeQueue(buffer.toList()))
        }
    }

    companion object {
        const val KEY = "adfinia.queue"
    }
}
