// EventQueueTest — mirrors `queue.test.ts`:
//   - flushes when flushAt is hit
//   - flushes on the interval
//   - drops events on 4xx (permanent failure)
//   - retries on 5xx with exponential backoff
//   - persists across re-construction
//   - caps the queue at maxQueueSize and drops oldest

package com.adfinia.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class RecordingTransport(
    private val result: TransportResult = TransportResult.OK,
) : AdfiniaTransport {
    val calls = mutableListOf<List<AdfiniaEnvelope>>()
    @Volatile var nextResult: TransportResult = result

    override suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult {
        synchronized(calls) { calls.add(batch) }
        return nextResult
    }

    val callCount get() = synchronized(calls) { calls.size }
    val flatSent get() = synchronized(calls) { calls.flatten() }
}

private class SequenceTransport(
    private val sequence: List<TransportResult>,
) : AdfiniaTransport {
    private val idx = AtomicInteger(0)
    val calls = AtomicInteger(0)
    override suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult {
        calls.incrementAndGet()
        val i = idx.getAndIncrement()
        return sequence.getOrElse(i) { sequence.last() }
    }
}

class EventQueueTest {

    private fun makePayload(event: String): AdfiniaPayload = AdfiniaPayload(
        type = AdfiniaPayloadType.TRACK,
        event = event,
        customerId = null,
        anonymousId = "anon-test",
        previousId = null,
        properties = null,
        traits = null,
        context = AdfiniaContext(libraryName = "adfinia-sdk-android", libraryVersion = "test"),
        sentAt = Iso8601.format(0L),
        messageId = "msg-$event",
    )

    private fun newQueue(
        store: AdfiniaKVStore = AdfiniaMemoryStore(),
        transport: AdfiniaTransport,
        flushAt: Int = 50,
        flushIntervalMs: Long = 5_000L,
        maxQueueSize: Int = 1_000,
    ): EventQueue = EventQueue(
        store = store,
        transport = transport,
        flushAt = flushAt,
        flushIntervalMs = flushIntervalMs,
        maxQueueSize = maxQueueSize,
        debug = {},
        context = Dispatchers.IO,
    )

    @Test
    fun `flushes when flushAt is hit`() = runBlocking {
        val transport = RecordingTransport()
        val q = newQueue(transport = transport, flushAt = 2, flushIntervalMs = 60_000L)
        try {
            q.enqueue(makePayload("a"))
            q.enqueue(makePayload("b"))
            waitFor { transport.callCount >= 1 }
            assertEquals(1, transport.callCount)
            assertEquals(2, transport.flatSent.size)
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `flushes on the interval`() = runBlocking {
        val transport = RecordingTransport()
        val q = newQueue(transport = transport, flushAt = 100, flushIntervalMs = 200L)
        try {
            q.enqueue(makePayload("a"))
            waitFor(timeoutMs = 2_000L) { transport.callCount >= 1 }
            assertEquals(1, transport.callCount)
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `drops events on permanent (4xx) failure`() = runBlocking {
        val transport = RecordingTransport(
            TransportResult(ok = false, permanent = true, status = 400),
        )
        val q = newQueue(transport = transport, flushAt = 100, flushIntervalMs = 60_000L)
        try {
            q.enqueue(makePayload("a"))
            q.flush()
            assertEquals(1, transport.callCount)
            assertEquals(0, q.size())
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `retries on transient (5xx) failure until success`() = runBlocking {
        val transport = SequenceTransport(listOf(
            TransportResult(ok = false, permanent = false, status = 502),
            TransportResult(ok = false, permanent = false, status = 503),
            TransportResult.OK,
        ))
        val q = newQueue(transport = transport, flushAt = 1, flushIntervalMs = 60_000L)
        try {
            q.enqueue(makePayload("a"))
            waitFor(timeoutMs = 5_000L) { transport.calls.get() >= 1 }
            // First retry after ~1s, second after ~2s. Allow generous slack.
            waitFor(timeoutMs = 8_000L) { transport.calls.get() >= 3 }
            assertTrue(transport.calls.get() >= 3)
            assertEquals(0, q.size())
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `keeps events buffered on transient failure`() = runBlocking {
        val transport = RecordingTransport(
            TransportResult(ok = false, permanent = false, status = 502),
        )
        val q = newQueue(transport = transport, flushAt = 100, flushIntervalMs = 60_000L)
        try {
            q.enqueue(makePayload("a"))
            q.enqueue(makePayload("b"))
            q.flush()
            assertEquals(1, transport.callCount)
            assertEquals(2, q.size())
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `persists buffered events across re-construction`() = runBlocking {
        val backing = AdfiniaMemoryStore()
        val neverOk = RecordingTransport(
            TransportResult(ok = false, permanent = false, status = 503),
        )
        val q1 = newQueue(
            store = backing,
            transport = neverOk,
            flushAt = 100,
            flushIntervalMs = 60_000L,
        )
        q1.enqueue(makePayload("a"))
        q1.enqueue(makePayload("b"))
        q1.destroy()

        val okTransport = RecordingTransport()
        val q2 = newQueue(
            store = backing,
            transport = okTransport,
            flushAt = 100,
            flushIntervalMs = 60_000L,
        )
        try {
            q2.flush()
            assertEquals(1, okTransport.callCount)
            assertEquals(2, okTransport.flatSent.size)
        } finally {
            q2.destroy()
        }
    }

    @Test
    fun `caps the queue at maxQueueSize and drops oldest`() = runBlocking {
        val transport = RecordingTransport(
            TransportResult(ok = false, permanent = false, status = 503),
        )
        val q = newQueue(
            transport = transport,
            flushAt = 1_000,
            flushIntervalMs = 60_000L,
            maxQueueSize = 3,
        )
        try {
            for (i in 0 until 6) q.enqueue(makePayload("e$i"))
            val drained = q.drainAll()
            assertEquals(3, drained.size)
            assertEquals("e3", drained[0].event)
            assertEquals("e5", drained[2].event)
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `flush on an empty queue is a no-op`() = runBlocking {
        val transport = RecordingTransport()
        val q = newQueue(transport = transport, flushAt = 1, flushIntervalMs = 60_000L)
        try {
            q.flush()
            assertEquals(0, transport.callCount)
        } finally {
            q.destroy()
        }
    }

    @Test
    fun `routes identify payloads to the identify path`() = runBlocking {
        val transport = RecordingTransport()
        val q = newQueue(transport = transport, flushAt = 1, flushIntervalMs = 60_000L)
        try {
            q.enqueue(makePayload("a").copy(type = AdfiniaPayloadType.IDENTIFY, event = null, customerId = "cust_42"))
            waitFor { transport.callCount >= 1 }
            val env = transport.flatSent.single()
            assertEquals("/api/v1/identify", env.path)
            assertTrue(env.body.contains("\"customer_id\""))
        } finally {
            q.destroy()
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                delay(10)
            }
        }
    }
}
