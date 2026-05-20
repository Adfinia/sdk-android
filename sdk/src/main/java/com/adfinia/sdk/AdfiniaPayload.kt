// Internal event shape. Mirrors the Web SDK's `AdfiniaPayload` 1:1 so the
// wire format is identical across platforms — only the transport layer
// translates to the API's `event_name` / `occurred_at` field names.

package com.adfinia.sdk

internal enum class AdfiniaPayloadType {
    TRACK, IDENTIFY, PAGE, SCREEN, ALIAS;

    fun wireValue(): String = when (this) {
        TRACK -> "track"
        IDENTIFY -> "identify"
        PAGE -> "page"
        SCREEN -> "screen"
        ALIAS -> "alias"
    }

    companion object {
        fun fromWire(s: String): AdfiniaPayloadType? = when (s) {
            "track" -> TRACK
            "identify" -> IDENTIFY
            "page" -> PAGE
            "screen" -> SCREEN
            "alias" -> ALIAS
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
