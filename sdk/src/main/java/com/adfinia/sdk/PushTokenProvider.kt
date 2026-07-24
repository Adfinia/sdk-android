// PushTokenProvider — the strategy that yields a platform push token to
// register with the backend.
//
// Two supported inputs (mirrors the flexibility of the RN SDK, which accepts
// either an Expo-fetched native token or a caller-supplied one):
//
//   1. Caller-supplied token — the host app already has an FCM token (e.g. it
//      runs its own FirebaseMessagingService) and passes it to
//      `Adfinia.registerForPush(token)`. No Firebase dependency needed inside
//      the SDK.
//
//   2. SDK-fetched FCM token — `Adfinia.registerForPush()` with no token asks
//      the default `FcmTokenProvider`, which pulls the current FCM
//      registration token from `FirebaseMessaging.getInstance()`.
//
// Firebase Cloud Messaging is an OPTIONAL (`compileOnly`) dependency — the
// analytics core must keep working in apps that don't ship FCM and in JVM
// unit tests where the classes are absent. We therefore reach FCM entirely by
// reflection so a missing `firebase-messaging` on the runtime classpath yields
// a clean `null` (→ TOKEN_FAILED / UNSUPPORTED) rather than a
// NoClassDefFoundError at class-load time.

package com.adfinia.sdk

/** Yields a push token, or null when none can be obtained. */
fun interface PushTokenProvider {
    /** Runs on a background dispatcher. Return null to signal "no token". */
    suspend fun token(): String?
}

/**
 * Default provider: fetches the current FCM registration token via
 * `FirebaseMessaging.getInstance().getToken()`, awaited with
 * `com.google.android.gms.tasks.Tasks.await(...)`. All reflective so the SDK
 * never hard-links Firebase. Returns null when FCM is not on the classpath or
 * the fetch fails.
 */
internal object FcmTokenProvider : PushTokenProvider {
    override suspend fun token(): String? = try {
        // FirebaseMessaging.getInstance()
        val fmClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
        val instance = fmClass.getMethod("getInstance").invoke(null)
        // instance.getToken() → com.google.android.gms.tasks.Task<String>
        val task = fmClass.getMethod("getToken").invoke(instance)
        // Tasks.await(task) blocks the (IO) thread and returns the String token.
        val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks")
        val taskClass = Class.forName("com.google.android.gms.tasks.Task")
        val awaited = tasksClass.getMethod("await", taskClass).invoke(null, task)
        (awaited as? String)?.ifBlank { null }
    } catch (_: Throwable) {
        // FCM absent (ClassNotFound) or fetch failed — caller maps null to a
        // typed failure. Never throws into the host app.
        null
    }
}
