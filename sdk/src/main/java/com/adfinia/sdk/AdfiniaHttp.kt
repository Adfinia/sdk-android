// AdfiniaHttp — a thin request/response JSON helper for the SDK's non-ingest
// control-plane calls (push registration + the in-app inbox). The analytics
// event pipeline has its own batching transport (`OkHttpTransport`); this
// helper is for the one-shot request/response endpoints that return a body we
// actually parse.
//
// It re-uses `OkHttpTransport.SHARED_CLIENT` so the connection pool +
// dispatcher threads are shared with the event flush path (no extra client),
// and stamps the same `Authorization: Bearer <writeKey>` +
// `X-Adfinia-SDK-Version` headers every other request carries.
//
// Every call is fully JVM-friendly: point it at a MockWebServer URL in unit
// tests and the real OkHttp stack executes off-device.

package com.adfinia.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal class AdfiniaHttp(
    private val host: String,
    private val writeKey: String,
    private val client: OkHttpClient = OkHttpTransport.SHARED_CLIENT,
    private val userAgent: String = "${BuildMeta.LIBRARY_NAME}/${BuildMeta.LIBRARY_VERSION}",
) {

    /** Outcome of a control-plane call. `body` is the raw response text (may be null). */
    data class Response(val ok: Boolean, val code: Int, val body: String?) {
        /** 4xx — the caller must not retry; the request itself was rejected. */
        val permanent: Boolean get() = code in 400..499
    }

    suspend fun post(path: String, jsonBody: String): Response = execute(
        newRequest(path).post(jsonBody.toRequestBody(JSON)).build(),
    )

    suspend fun get(path: String, query: Map<String, String?> = emptyMap()): Response = execute(
        newRequest(path, query).get().build(),
    )

    suspend fun delete(path: String): Response = execute(
        newRequest(path).delete().build(),
    )

    private fun newRequest(path: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val base = (host.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Adfinia: invalid host/path: $host$path")
        val url = base.newBuilder().apply {
            for ((k, v) in query) if (!v.isNullOrEmpty()) addQueryParameter(k, v)
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("User-Agent", userAgent)
            .header("X-Adfinia-SDK-Version", BuildMeta.SDK_VERSION_HEADER)
    }

    private suspend fun execute(req: Request): Response = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { res ->
                Response(ok = res.isSuccessful, code = res.code, body = res.body?.string())
            }
        } catch (_: IOException) {
            Response(ok = false, code = 0, body = null)
        } catch (_: Throwable) {
            Response(ok = false, code = 0, body = null)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
