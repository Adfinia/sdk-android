// AdfiniaClient — internal coordinator. The `Adfinia` object's static
// surface delegates to a process-wide singleton; advanced consumers
// (multi-tenant SSR / test isolation) can construct private instances.
//
// Concurrency model:
//   - The client itself is thread-safe — all enqueue paths land in
//     `EventQueue`, which is `Mutex`-guarded.
//   - Identity reads / writes go through `IdentityStore`, which is
//     `@Synchronized`.
//   - `init()` is single-shot and guarded by an internal flag.

package com.adfinia.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional dependency overrides — used by tests to inject deterministic
 * storage / transport. Not part of the public surface.
 */
class AdfiniaHooks(
    val store: AdfiniaKVStore? = null,
    val transport: AdfiniaTransport? = null,
    val nowMs: (() -> Long)? = null,
    /** Skip WorkManager registration. Tests + JVM unit tests set this. */
    val skipBackgroundWorker: Boolean = false,
)

class AdfiniaClient(private val hooks: AdfiniaHooks = AdfiniaHooks()) {

    @Volatile private var config: AdfiniaConfig? = null
    private val initialised = AtomicBoolean(false)
    private var identity: IdentityStore? = null
    private var queue: EventQueue? = null
    private var context: AdfiniaContext? = null
    private val now: () -> Long = hooks.nowMs ?: { System.currentTimeMillis() }

    /**
     * Scope for the fire-and-forget GET /api/v1/sdk/config call on init.
     * SupervisorJob so a failed fetch doesn't cancel sibling work; IO
     * dispatcher because OkHttp's blocking call is the easiest path.
     */
    private val configFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initialise the SDK. Idempotent — subsequent calls are logged and ignored
     * so a host app accidentally calling `initialize()` from two entry points
     * doesn't reset the identity / queue.
     */
    fun initialize(appContext: Context?, config: AdfiniaConfig) {
        if (!initialised.compareAndSet(false, true)) {
            log("initialize() called twice — ignoring")
            return
        }
        if (config.writeKey.isBlank()) {
            initialised.set(false)
            throw IllegalArgumentException("Adfinia: writeKey is required")
        }
        this.config = config

        val store: AdfiniaKVStore = hooks.store
            ?: appContext?.let { SharedPrefsStore(it.applicationContext) }
            ?: AdfiniaMemoryStore()

        val transport: AdfiniaTransport = hooks.transport
            ?: OkHttpTransport(host = config.host.trimEnd('/'), writeKey = config.writeKey)

        this.identity = IdentityStore(store)
        this.context = ContextBuilder.build(appContext?.applicationContext)
        this.queue = EventQueue(
            store = store,
            transport = transport,
            flushAt = config.flushAt.coerceAtLeast(1),
            flushIntervalMs = config.flushIntervalMs.coerceAtLeast(100L),
            maxQueueSize = config.maxQueueSize.coerceAtLeast(10),
            debug = ::log,
        )

        // Best-effort WorkManager registration — only when a real Context is
        // available (i.e. not in JVM unit tests).
        if (!hooks.skipBackgroundWorker && appContext != null) {
            try {
                FlushWorker.schedule(appContext.applicationContext)
            } catch (t: Throwable) {
                log("WorkManager registration skipped: ${t.message}")
            }
        }
        log("initialised host=${config.host}")

        // Best-effort: pull per-tenant runtime config from the server. The
        // server endpoint (GET /api/v1/sdk/config) returns batch_size /
        // flush_interval_ms / sampling_rate / breaker thresholds; we apply
        // the knobs we understand and ignore the rest (forward-compat: an
        // older SDK never breaks when the server adds a new field).
        //
        // Fire-and-forget on a background coroutine — a slow / failing
        // config fetch never blocks first-event delivery. Tests skip this
        // by setting `hooks.skipBackgroundWorker=true` to avoid network IO.
        if (!hooks.skipBackgroundWorker) {
            configFetchScope.launch {
                fetchRemoteConfig(config.host, config.writeKey)
            }
        }
    }

    /**
     * Hit GET /api/v1/sdk/config and apply the knobs we recognise. Soft-
     * fails on every error path — the local defaults stay.
     */
    private fun fetchRemoteConfig(host: String, writeKey: String) {
        val url = host.trimEnd('/') + "/api/v1/sdk/config"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $writeKey")
            .header("X-Adfinia-SDK-Version", BuildMeta.SDK_VERSION_HEADER)
            .get()
            .build()
        try {
            CONFIG_CLIENT.newCall(req).execute().use { res ->
                if (res.code == 426) {
                    log("SDK below the server minimum — please upgrade adfinia-sdk-android")
                    return
                }
                if (!res.isSuccessful) return
                val body = res.body?.string() ?: return
                val parsed = JSONObject(body)
                val batchSize = if (parsed.has("batch_size")) parsed.optInt("batch_size") else null
                val flushIntervalMs = if (parsed.has("flush_interval_ms")) parsed.optLong("flush_interval_ms") else null
                queue?.applyRemoteConfig(batchSize, flushIntervalMs)
                log("remote config applied batch=$batchSize intervalMs=$flushIntervalMs")
            }
        } catch (_: IOException) {
            log("remote config fetch failed (io) — sticking with defaults")
        } catch (_: Throwable) {
            log("remote config fetch failed — sticking with defaults")
        }
    }

    fun identify(arg: AdfiniaIdentifyArg, extraTraits: AdfiniaTraits?) {
        if (!guard("identify")) return
        val identity = this.identity ?: return
        val customerId: String?
        val anonymousId: String?
        val traits: AdfiniaTraits?
        when (arg) {
            is AdfiniaIdentifyArg.CustomerId -> {
                customerId = arg.id
                anonymousId = null
                traits = extraTraits
            }
            is AdfiniaIdentifyArg.Object -> {
                customerId = arg.customerId
                anonymousId = arg.anonymousId
                traits = mergeTraits(arg.traits, extraTraits)
            }
        }
        identity.identify(customerId, traits, anonymousId)
        enqueue(
            type = AdfiniaPayloadType.IDENTIFY,
            event = null,
            customerId = identity.customerId,
            anonymousId = identity.anonymousId,
            previousId = null,
            properties = null,
            traits = identity.traits,
        )
    }

    fun track(event: String, properties: AdfiniaProperties?) {
        if (!guard("track")) return
        if (event.isBlank()) {
            log("track() called without an event — dropped")
            return
        }
        val id = identity ?: return
        enqueue(
            type = AdfiniaPayloadType.TRACK,
            event = event,
            customerId = id.customerId,
            anonymousId = id.anonymousId,
            previousId = null,
            properties = properties,
            traits = null,
        )
    }

    fun screen(name: String?, properties: AdfiniaProperties?) {
        if (!guard("screen")) return
        val id = identity ?: return
        enqueue(
            type = AdfiniaPayloadType.SCREEN,
            event = name,
            customerId = id.customerId,
            anonymousId = id.anonymousId,
            previousId = null,
            properties = properties,
            traits = null,
        )
    }

    fun alias(newId: String, previousId: String?) {
        if (!guard("alias")) return
        if (newId.isBlank()) {
            log("alias() called without a newId — dropped")
            return
        }
        val id = identity ?: return
        val prev = previousId ?: id.customerId ?: id.anonymousId
        enqueue(
            type = AdfiniaPayloadType.ALIAS,
            event = null,
            customerId = newId,
            anonymousId = id.anonymousId,
            previousId = prev,
            properties = null,
            traits = null,
        )
        id.identify(newId, null, null)
    }

    fun reset() {
        if (!initialised.get()) return
        identity?.reset()
        log("identity reset — new anonymous_id minted")
    }

    suspend fun flush() {
        if (!initialised.get()) return
        queue?.flush()
    }

    /**
     * Convenience for Java callers and lifecycle hooks that aren't running
     * in a coroutine scope. Blocks the calling thread.
     */
    fun flushBlocking() {
        if (!initialised.get()) return
        runBlocking { queue?.flush() }
    }

    // ---------- internal test surface ----------

    /** @suppress */
    internal fun _identity(): IdentityStore? = identity

    /** @suppress */
    internal fun _queue(): EventQueue? = queue

    /** @suppress */
    internal fun _isInitialised(): Boolean = initialised.get()

    /** @suppress — internal use only. Tears down a client instance so a fresh
     * one can be wired up in the next test. Public API is single-shot init. */
    internal fun _shutdownForTesting() {
        queue?.destroy()
        queue = null
        identity = null
        config = null
        context = null
        initialised.set(false)
    }

    // ---------- internal ----------

    private fun enqueue(
        type: AdfiniaPayloadType,
        event: String?,
        customerId: String?,
        anonymousId: String,
        previousId: String?,
        properties: AdfiniaProperties?,
        traits: AdfiniaTraits?,
    ) {
        val ctx = this.context ?: ContextBuilder.build(null)
        queue?.enqueue(
            AdfiniaPayload(
                type = type,
                event = event,
                customerId = customerId,
                anonymousId = anonymousId,
                previousId = previousId,
                properties = properties,
                traits = traits,
                context = ctx,
                sentAt = Iso8601.format(now()),
                messageId = UuidV7.generate(),
            ),
        )
    }

    private fun guard(label: String): Boolean {
        val cfg = config
        if (cfg == null || !initialised.get()) {
            try {
                Log.w("Adfinia", "$label() called before initialize()")
            } catch (_: Throwable) {
                // Log unavailable off-Android — fall through.
            }
            return false
        }
        val consent = cfg.consent ?: return true
        return try {
            consent.isGranted()
        } catch (_: Throwable) {
            // Fail-closed — if the consent gate crashes, drop the event.
            false
        }
    }

    private fun mergeTraits(a: AdfiniaTraits?, b: AdfiniaTraits?): AdfiniaTraits? {
        if (a == null) return b
        if (b == null) return a
        return a + b
    }

    private fun log(message: String) {
        if (config?.debug != true) return
        try {
            Log.d("Adfinia", message)
        } catch (_: Throwable) {
            // Log unavailable off-Android — swallow.
        }
    }

    companion object {
        /**
         * Shared OkHttpClient for the lightweight GET /api/v1/sdk/config
         * call. Kept separate from `OkHttpTransport.SHARED_CLIENT` so a
         * stalled event flush can't block config refreshes (and vice
         * versa). Short timeouts — config fetches that take >5s on init
         * aren't worth waiting on.
         */
        private val CONFIG_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}
