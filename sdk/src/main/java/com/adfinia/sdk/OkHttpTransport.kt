// OkHttpTransport — production HTTP transport, backed by a single shared
// OkHttpClient. We deliberately keep one client process-wide so the
// connection pool, dispatcher, and idle threads are reused across every
// SDK instance and across foreground/background flushes (R-NEXT-AND-2).
//
// AGENT-SDK-INGEST-KAFKA (2026-05-21) — switched to the batch endpoints:
//   - All identify   → POST /api/v1/identify/batch
//   - All track-like → POST /api/v1/track/batch
//   - Mixed batch    → one batch per kind, parallel
//
// The queue already encodes per-event JSON; we wrap into `{"events":[...]}`
// here. ALWAYS batches — even a single event ships as a 1-element
// `{"events":[...]}`. The legacy single-event endpoints (/api/v1/track,
// /api/v1/identify) are NOT used: the single /track path does not stamp the
// event environment from the authenticating API key (it defaults to 'live'),
// so a `adf_test_` key's lone event would be mis-tagged and leak into live
// analytics. The batch endpoints stamp environment from the key. Mirrors
// @adfinia/sdk-web 1.3.1 + @adfinia/sdk-react-native 1.0.1.

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
        // Always batch — a single event ships as a 1-element {"events":[...]}
        // through the batch endpoints below (never single /track). See the file
        // header for why the single-event endpoints are not used.
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
