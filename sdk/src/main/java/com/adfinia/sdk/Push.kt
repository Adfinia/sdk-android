// Push registration — the Android counterpart to the RN SDK's `pushNative.ts`.
//
// Flow (identical wire contract to @adfinia/sdk-react-native):
//   obtain the platform push token (FCM on Android) → POST it to
//   /api/v1/push/register with the SDK's current identity attached → emit a
//   `push_registered` track event.
//
// Wire body — mirrors RN exactly:
//   {
//     token, platform: "android",
//     device_id,            // == anonymous_id (device/install-scoped, stable)
//     app_version,
//     customer_id?, external_id?, anonymous_id
//   }
//
// The last-registered token is cached in the KV store so (a) unregister() knows
// which token to DELETE and (b) onNewPushToken() can skip a redundant re-POST
// when FCM hands back an unchanged token.

package com.adfinia.sdk

/** Identity bag handed to the push module. Mirrors the RN PushBridge identity. */
internal data class PushIdentity(
    val customerId: String?,
    val externalId: String?,
    val anonymousId: String,
)

/** Why a push registration attempt did not complete. Mirrors RN's reasons. */
enum class RegisterPushFailureReason {
    /** initialize() has not been called. */
    NOT_INITIALISED,
    /** No push token: FCM not on the classpath, or the fetch returned nothing. */
    TOKEN_FAILED,
    /** The server rejected the registration POST. */
    POST_FAILED,
}

/** Outcome of registerForPush() / unregisterForPush(). */
sealed class RegisterPushResult {
    /** Token registered (or, for unregister, the token was removed). */
    data class Success(val token: String) : RegisterPushResult()
    data class Failure(
        val reason: RegisterPushFailureReason,
        val detail: String? = null,
    ) : RegisterPushResult()
}

internal class PushManager(
    private val http: AdfiniaHttp,
    private val store: AdfiniaKVStore,
    private val identity: () -> PushIdentity,
    private val appVersion: () -> String?,
    private val defaultTokenProvider: PushTokenProvider,
    private val track: (event: String, properties: AdfiniaProperties?) -> Unit,
    private val debug: (String) -> Unit,
) {
    /**
     * Register for push. When [callerToken] is null the default FCM provider
     * fetches the token; otherwise the supplied token is used verbatim.
     * Idempotent per token — re-registering the same token still POSTs (the
     * server upsert refreshes last_seen), but onNewPushToken() dedupes.
     */
    suspend fun register(callerToken: String?): RegisterPushResult {
        val token = (callerToken?.ifBlank { null }) ?: defaultTokenProvider.token()
        if (token.isNullOrBlank()) {
            debug("push: no token available (FCM absent or fetch failed)")
            return RegisterPushResult.Failure(RegisterPushFailureReason.TOKEN_FAILED)
        }
        return postToken(token)
    }

    /**
     * Re-register when FCM rotates the token. Host apps call this from their
     * `FirebaseMessagingService.onNewToken(token)`. No-op when the token is
     * unchanged from the last registration.
     */
    suspend fun onNewToken(token: String): RegisterPushResult {
        if (token.isBlank()) {
            return RegisterPushResult.Failure(RegisterPushFailureReason.TOKEN_FAILED)
        }
        if (token == store.get(KEY_TOKEN)) {
            debug("push: onNewToken unchanged — skipping re-register")
            return RegisterPushResult.Success(token)
        }
        return postToken(token)
    }

    /** Remove the last-registered token from the backend. */
    suspend fun unregister(): RegisterPushResult {
        val token = store.get(KEY_TOKEN)
        if (token.isNullOrBlank()) {
            debug("push: unregister with no registered token — no-op")
            return RegisterPushResult.Failure(RegisterPushFailureReason.TOKEN_FAILED, "no registered token")
        }
        val encoded = java.net.URLEncoder.encode(token, "UTF-8")
        val res = http.delete("$UNREGISTER_PATH/$encoded")
        // 404 (token already gone) is a benign success for unregister.
        if (!res.ok && res.code != 404) {
            debug("push: unregister DELETE failed status=${res.code}")
            return RegisterPushResult.Failure(RegisterPushFailureReason.POST_FAILED, res.code.toString())
        }
        store.remove(KEY_TOKEN)
        track("push_unregistered", mapOf("platform" to PLATFORM))
        debug("push: unregistered")
        return RegisterPushResult.Success(token)
    }

    private suspend fun postToken(token: String): RegisterPushResult {
        val id = identity()
        val body = buildRegisterBody(token, id, appVersion())
        val res = http.post(REGISTER_PATH, body)
        if (!res.ok) {
            debug("push: registration POST failed status=${res.code}")
            return RegisterPushResult.Failure(RegisterPushFailureReason.POST_FAILED, res.code.toString())
        }
        store.set(KEY_TOKEN, token)
        track("push_registered", mapOf("platform" to PLATFORM))
        debug("push: registered")
        return RegisterPushResult.Success(token)
    }

    companion object {
        const val PLATFORM = "android"
        const val REGISTER_PATH = "/api/v1/push/register"
        const val UNREGISTER_PATH = "/api/v1/push/register"
        const val KEY_TOKEN = "adfinia.push_token"

        /**
         * Builds the /push/register JSON body. Pulled out (and internal) so the
         * unit test asserts the exact wire shape without a live network. The
         * `device_id` doubles as the anonymous_id (device/install-scoped) so
         * the backend can de-dupe tokens per device — identical to RN.
         */
        internal fun buildRegisterBody(token: String, id: PushIdentity, appVersion: String?): String {
            val o = org.json.JSONObject()
            o.put("token", token)
            o.put("platform", PLATFORM)
            o.put("device_id", id.anonymousId)
            if (appVersion != null) o.put("app_version", appVersion)
            if (id.customerId != null) o.put("customer_id", id.customerId)
            if (id.externalId != null) o.put("external_id", id.externalId)
            o.put("anonymous_id", id.anonymousId)
            return o.toString()
        }
    }
}
