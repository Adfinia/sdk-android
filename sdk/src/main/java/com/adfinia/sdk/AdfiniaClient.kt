// AdfiniaClient — internal coordinator. The `Adfinia` object's static
// surface delegates to a singleton. Advanced consumers can construct a
// private instance for multi-tenant SSR / test isolation.
//
// Skeleton: in-memory only. NEXT.md tracks the real OkHttp transport,
// SharedPreferences/Room persistence, and exponential backoff scheduler.

package com.adfinia.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AdfiniaClient {
    @Volatile private var config: AdfiniaConfig? = null
    private val identity = IdentityStore()
    private val queue = ArrayDeque<AdfiniaPayload>()
    private val queueLock = Mutex()

    fun initialize(appContext: Context, config: AdfiniaConfig) {
        if (this.config != null) {
            log("initialize() called twice — ignoring")
            return
        }
        // appContext kept here so NEXT-AND-3 (SharedPreferences) can attach.
        this.config = config
        log("initialised host=${config.host}")
    }

    fun identify(arg: AdfiniaIdentifyArg, extraTraits: AdfiniaTraits?) {
        if (!guard("identify")) return
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
            AdfiniaPayload(
                type = AdfiniaPayloadType.IDENTIFY,
                event = null,
                customerId = identity.customerId,
                anonymousId = identity.anonymousId,
                previousId = null,
                properties = null,
                traits = identity.traits,
            )
        )
    }

    fun track(event: String, properties: AdfiniaProperties?) {
        if (!guard("track")) return
        if (event.isBlank()) {
            log("track() called without an event — dropped")
            return
        }
        enqueue(
            AdfiniaPayload(
                type = AdfiniaPayloadType.TRACK,
                event = event,
                customerId = identity.customerId,
                anonymousId = identity.anonymousId,
                previousId = null,
                properties = properties,
                traits = null,
            )
        )
    }

    fun screen(name: String?, properties: AdfiniaProperties?) {
        if (!guard("screen")) return
        enqueue(
            AdfiniaPayload(
                type = AdfiniaPayloadType.SCREEN,
                event = name,
                customerId = identity.customerId,
                anonymousId = identity.anonymousId,
                previousId = null,
                properties = properties,
                traits = null,
            )
        )
    }

    fun alias(newId: String, previousId: String?) {
        if (!guard("alias")) return
        if (newId.isBlank()) return
        val prev = previousId ?: identity.customerId ?: identity.anonymousId
        enqueue(
            AdfiniaPayload(
                type = AdfiniaPayloadType.ALIAS,
                event = null,
                customerId = newId,
                anonymousId = identity.anonymousId,
                previousId = prev,
                properties = null,
                traits = null,
            )
        )
        identity.identify(newId, null, null)
    }

    fun reset() {
        if (config == null) return
        identity.reset()
    }

    suspend fun flush() {
        if (config == null) return
        // Skeleton: drain and discard. NEXT-AND-2 wires the real transport.
        queueLock.withLock {
            val drained = queue.size
            queue.clear()
            log("flushed $drained event(s) [skeleton — no network]")
        }
    }

    private fun enqueue(payload: AdfiniaPayload) {
        synchronized(queue) {
            queue.addLast(payload)
            val max = config?.maxQueueSize ?: 1_000
            while (queue.size > max) queue.removeFirst()
        }
    }

    private fun guard(label: String): Boolean {
        val cfg = config ?: run {
            Log.w("Adfinia", "$label() called before initialize()")
            return false
        }
        val consent = cfg.consent ?: return true
        return try {
            consent.isGranted()
        } catch (_: Throwable) {
            false
        }
    }

    private fun mergeTraits(a: AdfiniaTraits?, b: AdfiniaTraits?): AdfiniaTraits? {
        if (a == null) return b
        if (b == null) return a
        return a + b
    }

    private fun log(message: String) {
        if (config?.debug == true) Log.d("Adfinia", message)
    }
}

internal data class AdfiniaPayload(
    val type: AdfiniaPayloadType,
    val event: String?,
    val customerId: String?,
    val anonymousId: String,
    val previousId: String?,
    val properties: AdfiniaProperties?,
    val traits: AdfiniaTraits?,
    val sentAtMs: Long = System.currentTimeMillis(),
    val messageId: String = UUID.randomUUID().toString(),
)

internal enum class AdfiniaPayloadType { TRACK, IDENTIFY, PAGE, SCREEN, ALIAS }
