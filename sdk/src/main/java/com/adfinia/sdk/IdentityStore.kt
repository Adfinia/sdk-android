// IdentityStore — owns the anonymous_id + customer_id + traits triple and
// is the sole writer for them. Persists to a KVStore so the identity
// survives a process kill / cold-start.
//
// Mirrors the Web SDK's `identity.ts`. The only Android-specific concern is
// the storage backend, which is injected via `AdfiniaKVStore`.

package com.adfinia.sdk

import org.json.JSONObject

internal class IdentityStore(private val store: AdfiniaKVStore) {
    @Volatile var anonymousId: String = ""
        private set
    @Volatile var customerId: String? = null
        private set
    @Volatile var traits: AdfiniaTraits? = null
        private set

    init {
        load()
    }

    @Synchronized
    private fun load() {
        val raw = store.get(KEY)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val anon = o.optString("anonymous_id").ifBlank { null }
                if (anon != null) {
                    anonymousId = anon
                    customerId = o.optString("customer_id").ifBlank { null }
                    traits = o.optJSONObject("traits")?.let(::traitsFromJson)
                    return
                }
            } catch (_: Throwable) {
                // Corrupt — fall through and mint fresh.
            }
        }
        anonymousId = UuidV7.generate()
        persist()
    }

    @Synchronized
    fun identify(customerId: String?, traits: AdfiniaTraits?, anonymousId: String?) {
        if (anonymousId != null) this.anonymousId = anonymousId
        if (customerId != null) this.customerId = customerId
        if (traits != null) {
            this.traits = if (this.traits != null) this.traits!! + traits else traits
        }
        persist()
    }

    @Synchronized
    fun reset() {
        anonymousId = UuidV7.generate()
        customerId = null
        traits = null
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        o.put("anonymous_id", anonymousId)
        customerId?.let { o.put("customer_id", it) }
        traits?.let { o.put("traits", PayloadCodec.toJson(it)) }
        store.set(KEY, o.toString())
    }

    private fun traitsFromJson(o: JSONObject): AdfiniaTraits = PayloadCodec.fromJson(o)

    companion object {
        const val KEY = "adfinia.identity"
    }
}
