// Adfinia — Android SDK public surface. Mirrors @adfinia/sdk-web and
// AdfiniaSDK (iOS) 1:1. Same method names, same arg shapes, same wire
// format.
//
// Skeleton: API stubs delegate to AdfiniaClient. Network transport,
// SharedPreferences persistence, and the backoff scheduler are placeholders
// tracked in NEXT.md.

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
    val writeKey: String,
    val host: String = "https://events.adfinia.com",
    val debug: Boolean = false,
    val consent: AdfiniaConsent? = null,
    val flushAt: Int = 50,
    val flushIntervalMs: Long = 5_000L,
    val maxQueueSize: Int = 1_000,
)

/** Identify argument — customer-id string or full object. */
sealed class AdfiniaIdentifyArg {
    data class CustomerId(val id: String) : AdfiniaIdentifyArg()
    data class Object(
        val customerId: String? = null,
        val anonymousId: String? = null,
        val traits: AdfiniaTraits? = null,
    ) : AdfiniaIdentifyArg()
}

/** Adfinia SDK entry point. Singleton — call `initialize` once at app startup. */
object Adfinia {
    @Volatile private var client: AdfiniaClient = AdfiniaClient()

    @JvmStatic
    fun initialize(context: Context, config: AdfiniaConfig) {
        client.initialize(context.applicationContext, config)
    }

    @JvmStatic
    @JvmOverloads
    fun identify(customerId: String, traits: AdfiniaTraits? = null) {
        client.identify(AdfiniaIdentifyArg.CustomerId(customerId), traits)
    }

    @JvmStatic
    @JvmOverloads
    fun identify(arg: AdfiniaIdentifyArg, traits: AdfiniaTraits? = null) {
        client.identify(arg, traits)
    }

    @JvmStatic
    @JvmOverloads
    fun track(event: String, properties: AdfiniaProperties? = null) {
        client.track(event, properties)
    }

    @JvmStatic
    @JvmOverloads
    fun screen(name: String? = null, properties: AdfiniaProperties? = null) {
        client.screen(name, properties)
    }

    @JvmStatic
    @JvmOverloads
    fun alias(newId: String, previousId: String? = null) {
        client.alias(newId, previousId)
    }

    @JvmStatic
    fun reset() {
        client.reset()
    }

    @JvmStatic
    suspend fun flush() {
        client.flush()
    }
}
