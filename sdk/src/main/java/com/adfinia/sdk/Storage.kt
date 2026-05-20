// KVStore — thin key/value abstraction so the SDK's core modules don't
// depend on Android's SharedPreferences directly. This lets us run unit
// tests on the JVM without Robolectric and gives advanced consumers a hook
// to plug encrypted storage (EncryptedSharedPreferences, MMKV, etc.).
//
// The Android-backed implementation lives in `SharedPrefsStore.kt` and is
// the default created by `AdfiniaClient` at init time.

package com.adfinia.sdk

import androidx.annotation.VisibleForTesting

interface AdfiniaKVStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/**
 * In-memory KV store. Used as a fallback when no Android `Context` is
 * available (host app uses the SDK from a unit test, JVM-only target, etc.)
 * and as the default backend for tests.
 */
class AdfiniaMemoryStore : AdfiniaKVStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun set(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }

    @VisibleForTesting
    @Synchronized
    fun clear() { map.clear() }
}
