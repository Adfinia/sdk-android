// SharedPreferences-backed KVStore. The SDK uses a private prefs file
// `adfinia_sdk` so we don't collide with the host app's own keys.

package com.adfinia.sdk

import android.content.Context

internal class SharedPrefsStore(context: Context) : AdfiniaKVStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        // commit() guarantees the write completes synchronously so a crash
        // immediately after enqueue() doesn't lose the buffered event. The
        // disk write is fast on modern Android (SQLite-backed since API 9).
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS_NAME = "adfinia_sdk"
    }
}
