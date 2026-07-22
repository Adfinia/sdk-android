// Adfinia — public Android SDK surface.
//
// Mirrors `@adfinia/sdk-web` and `AdfiniaSDK` (iOS) 1:1. Same method names,
// same argument shapes, same wire format. The object holds a single
// process-wide `AdfiniaClient`; multi-tenant SSR consumers can construct
// extra `AdfiniaClient` instances directly.
//
// Every public method is `@JvmStatic`-exposed for clean Java interop, with
// `@JvmOverloads` on every optional-parameter method.

package com.adfinia.sdk

import android.content.Context

/** Public alias — JSON-shaped property bag. */
typealias AdfiniaProperties = Map<String, Any?>
typealias AdfiniaTraits = Map<String, Any?>

/** Returns true if the user has granted analytics consent. */
fun interface AdfiniaConsent {
    fun isGranted(): Boolean
}

/** SDK configuration. */
data class AdfiniaConfig(
    /**
     * Tenant write-only public key — `pk_live_…` or `pk_test_…`. Safe to
     * bundle in client-side code; the server enforces per-tenant scoping.
     */
    val writeKey: String,
    /** Ingest host. Defaults to the Adfinia production ingest endpoint. */
    val host: String = "https://api.adfinia.com",
    /** Emit SDK internals to `Log.d("Adfinia", …)`. */
    val debug: Boolean = false,
    /** Consent gate. Called on every public API entry — see {@link AdfiniaConsent}. */
    val consent: AdfiniaConsent? = null,
    /** Buffer size that triggers an immediate flush. Default 50. */
    val flushAt: Int = 50,
    /** Background flush interval in milliseconds. Default 5000 (5s). */
    val flushIntervalMs: Long = 5_000L,
    /** Cap on the in-memory + on-disk queue. Default 1000 — oldest dropped on overflow. */
    val maxQueueSize: Int = 1_000,
)

/** identify() argument — either a customer_id string or a full object. */
sealed class AdfiniaIdentifyArg {
    data class CustomerId(val id: String) : AdfiniaIdentifyArg()
    data class Object(
        val customerId: String? = null,
        val anonymousId: String? = null,
        val traits: AdfiniaTraits? = null,
    ) : AdfiniaIdentifyArg()
}

/**
 * Adfinia SDK entry point. Call `Adfinia.initialize(...)` once at app startup.
 * All other methods are no-ops until `initialize` has been called.
 */
object Adfinia {

    /**
     * Process-wide client. Exposed so test fixtures and the WorkManager
     * background worker can reach the same instance. Tests use
     * `installForTesting()` to swap in an isolated client.
     */
    @Volatile
    private var _client: AdfiniaClient = AdfiniaClient()

    @JvmStatic
    val client: AdfiniaClient
        get() = _client

    @JvmStatic
    fun initialize(context: Context, config: AdfiniaConfig) {
        _client.initialize(context.applicationContext, config)
    }

    /** Identify the current user by customer-id, optionally with traits. */
    @JvmStatic
    @JvmOverloads
    fun identify(customerId: String, traits: AdfiniaTraits? = null) {
        client.identify(AdfiniaIdentifyArg.CustomerId(customerId), traits)
    }

    /** Identify the current user with the full object form. */
    @JvmStatic
    @JvmOverloads
    fun identify(arg: AdfiniaIdentifyArg, traits: AdfiniaTraits? = null) {
        client.identify(arg, traits)
    }

    /** Track an event with optional properties. */
    @JvmStatic
    @JvmOverloads
    fun track(event: String, properties: AdfiniaProperties? = null) {
        client.track(event, properties)
    }

    /** Mobile analogue of `page()`. */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String? = null, properties: AdfiniaProperties? = null) {
        client.screen(name, properties)
    }

    /**
     * Deprecated. `alias()` is a no-op: there is no server-side handler for
     * alias/previous_id, and anonymous-to-known promotion already happens
     * automatically inside `identify()` (the SDK ships the live anonymous_id
     * on the identify event). Call `identify()` instead.
     */
    @Deprecated(
        "alias() is a no-op (no server-side handler); anonymous sessions are promoted automatically by identify()",
        ReplaceWith("identify(newId)"),
    )
    @JvmStatic
    @JvmOverloads
    fun alias(newId: String, previousId: String? = null) {
        @Suppress("DEPRECATION")
        client.alias(newId, previousId)
    }

    /** Logout — mints a fresh anonymous_id and clears the customer_id + traits. */
    @JvmStatic
    fun reset() {
        client.reset()
    }

    /** Drain the buffered queue. Returns when the in-flight batch resolves. */
    @JvmStatic
    suspend fun flush() {
        client.flush()
    }

    /**
     * Blocking variant of `flush()` for Java callers that don't have a
     * coroutine scope handy. Blocks the calling thread until the flush
     * completes. Prefer `flush()` from Kotlin.
     */
    @JvmStatic
    fun flushBlocking() {
        client.flushBlocking()
    }

    /** @suppress — internal use only. */
    @JvmStatic
    internal fun installForTesting(replacement: AdfiniaClient) {
        _client._shutdownForTesting()
        _client = replacement
    }
}
