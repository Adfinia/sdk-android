// OkHttpTransport — production HTTP transport, backed by a single shared
// OkHttpClient. We deliberately keep one client process-wide so the
// connection pool, dispatcher, and idle threads are reused across every
// SDK instance and across foreground/background flushes (R-NEXT-AND-2).
//
// AGENT-SDK-INGEST-KAFKA (2026-05-21) — switched to the batch endpoints:
//   - 1 event              → POST /api/v1/{track,identify} (legacy)
//   - All identify (N>1)   → POST /api/v1/identify/batch
//   - All track-like (N>1) → POST /api/v1/track/batch
//   - Mixed batch          → one batch per kind, parallel
//
// The queue already encodes per-event JSON; we wrap into `{"events":[...]}`
// here. The single-event shortcut keeps offline-drain flushes (1-3 events)
// off the batch overhead.

package com.adfinia.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpTransport(
    private val host: String,
    private val writeKey: String,
    private val client: OkHttpClient = SHARED_CLIENT,
    private val userAgent: String = "${BuildMeta.LIBRARY_NAME}/${BuildMeta.LIBRARY_VERSION}",
) : AdfiniaTransport {

    override suspend fun send(batch: List<AdfiniaEnvelope>): TransportResult {
        if (batch.isEmpty()) return TransportResult.OK
        if (batch.size == 1) {
            return sendOne(batch[0])
        }
        return coroutineScope {
            val identifies = batch.filter { it.kind == AdfiniaEnvelopeKind.IDENTIFY }
            val tracks = batch.filter { it.kind == AdfiniaEnvelopeKind.TRACK }
            val calls = buildList {
                if (identifies.isNotEmpty()) add(async(Dispatchers.IO) { sendBatch("/api/v1/identify/batch", identifies) })
                if (tracks.isNotEmpty()) add(async(Dispatchers.IO) { sendBatch("/api/v1/track/batch", tracks) })
            }
            val results = calls.awaitAll()
            var ok = true
            var permanent = false
            var status: Int? = null
            for (r in results) {
                if (!r.ok) {
                    ok = false
                    if (r.permanent) permanent = true
                    status = r.status ?: status
                }
            }
            TransportResult(ok = ok, permanent = permanent, status = status)
        }
    }

    private suspend fun sendBatch(path: String, envelopes: List<AdfiniaEnvelope>): TransportResult = withContext(Dispatchers.IO) {
        val url = host.trimEnd('/') + path
        // Concatenate the already-encoded per-event JSON bodies into the
        // {"events":[...]} envelope without re-parsing — each `body` is
        // already a valid JSON object.
        val sb = StringBuilder()
        sb.append("{\"events\":[")
        envelopes.forEachIndexed { i, env ->
            if (i > 0) sb.append(',')
            sb.append(env.body)
        }
        sb.append("]}")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-Adfinia-SDK-Version", BuildMeta.SDK_VERSION_HEADER)
            .post(sb.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { res ->
                val code = res.code
                if (res.isSuccessful) {
                    TransportResult(ok = true, permanent = false, status = code)
                } else if (code in 400..499) {
                    TransportResult(ok = false, permanent = true, status = code)
                } else {
                    TransportResult(ok = false, permanent = false, status = code)
                }
            }
        } catch (_: IOException) {
            TransportResult(ok = false, permanent = false, status = null)
        } catch (_: Throwable) {
            TransportResult(ok = false, permanent = false, status = null)
        }
    }

    private suspend fun sendOne(env: AdfiniaEnvelope): TransportResult = withContext(Dispatchers.IO) {
        val path = if (env.kind == AdfiniaEnvelopeKind.IDENTIFY) "/api/v1/identify" else "/api/v1/track"
        val url = host.trimEnd('/') + path
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-Adfinia-SDK-Version", BuildMeta.SDK_VERSION_HEADER)
            .post(env.body.toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { res ->
                val code = res.code
                if (res.isSuccessful) {
                    TransportResult(ok = true, permanent = false, status = code)
                } else if (code in 400..499) {
                    TransportResult(ok = false, permanent = true, status = code)
                } else {
                    TransportResult(ok = false, permanent = false, status = code)
                }
            }
        } catch (_: IOException) {
            // Network failure — retry.
            TransportResult(ok = false, permanent = false, status = null)
        } catch (_: Throwable) {
            TransportResult(ok = false, permanent = false, status = null)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Process-wide shared client — re-used across every Adfinia SDK
         * instance and across the foreground client + the WorkManager
         * background worker. Avoids leaking dispatcher threads.
         */
        val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
