// Internal event shape. Mirrors the Web SDK's `AdfiniaPayload` 1:1 so the
// wire format is identical across platforms — only the transport layer
// translates to the API's `event_name` / `occurred_at` field names.

package com.adfinia.sdk

// ALIAS was removed in 1.1.0: alias() is a deprecated no-op (no server-side
// handler), so no alias payload is ever produced. A persisted "alias" envelope
// from an older build now decodes to null (dropped) via fromWire().
internal enum class AdfiniaPayloadType {
    TRACK, IDENTIFY, PAGE, SCREEN;

    fun wireValue(): String = when (this) {
        TRACK -> "track"
        IDENTIFY -> "identify"
        PAGE -> "page"
        SCREEN -> "screen"
    }

    companion object {
        fun fromWire(s: String): AdfiniaPayloadType? = when (s) {
            "track" -> TRACK
            "identify" -> IDENTIFY
            "page" -> PAGE
            "screen" -> SCREEN
            else -> null
        }
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
    val context: AdfiniaContext,
    val sentAt: String,
    val messageId: String,
)

internal data class AdfiniaContext(
    val libraryName: String,
    val libraryVersion: String,
    val locale: String? = null,
    val timezone: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val networkType: String? = null,
)
