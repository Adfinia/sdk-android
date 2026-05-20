// OkHttpTransport — production HTTP transport, backed by a single shared
// OkHttpClient. We deliberately keep one client process-wide so the
// connection pool, dispatcher, and idle threads are reused across every
// SDK instance and across foreground/background flushes (R-NEXT-AND-2).
//
// The API's `/api/v1/identify` and `/api/v1/track` endpoints are single-
// event today (see api/api/openapi.yaml § AGENT-CDP-IDENTITY 2026-05-19),
// so a batch is fanned out into one request per envelope and the worst
// outcome wins. When the bulk endpoint lands, swap in a single POST here.

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
        return coroutineScope {
            val results = batch.map { env ->
                async(Dispatchers.IO) { sendOne(env) }
            }.awaitAll()
            // Worst result wins — any permanent failure ⇒ permanent so the
            // queue drops only on a true 4xx; any retryable failure ⇒ retry.
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

    private suspend fun sendOne(env: AdfiniaEnvelope): TransportResult = withContext(Dispatchers.IO) {
        val url = host.trimEnd('/') + env.path
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
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
