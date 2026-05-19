// IdentityStore — owns anonymous_id + customer_id + traits.
// Skeleton: in-memory only. NEXT-AND-3 wires this to a SharedPreferences
// file `adfinia_sdk` so the identity persists across cold-starts.

package com.adfinia.sdk

import java.util.UUID

internal class IdentityStore {
    @Volatile var anonymousId: String = UUID.randomUUID().toString()
        private set
    @Volatile var customerId: String? = null
        private set
    @Volatile var traits: AdfiniaTraits? = null
        private set

    @Synchronized
    fun identify(customerId: String?, traits: AdfiniaTraits?, anonymousId: String?) {
        if (anonymousId != null) this.anonymousId = anonymousId
        if (customerId != null) this.customerId = customerId
        if (traits != null) {
            this.traits = if (this.traits != null) this.traits!! + traits else traits
        }
        // TODO NEXT-AND-3: persist().
    }

    @Synchronized
    fun reset() {
        anonymousId = UUID.randomUUID().toString()
        customerId = null
        traits = null
        // TODO NEXT-AND-3: persist().
    }
}
