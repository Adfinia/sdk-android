// Build-time constants. Bumped by the release script alongside the
// `mavenPublishing.coordinates(...)` version in `sdk/build.gradle.kts`.
//
// `SDK_VERSION_HEADER` is the value the SDK sends in the
// `X-Adfinia-SDK-Version` HTTP header. Shape (server contract, see
// api/internal/identity/sdk_config_handler.go):
//
//     X-Adfinia-SDK-Version: adfinia-sdk-android@1.0.0
//
// The server's SDKVersionMiddleware enforces the minimum supported
// version per SDK; below the floor → 426 Upgrade Required.

package com.adfinia.sdk

internal object BuildMeta {
    const val LIBRARY_NAME: String = "adfinia-sdk-android"
    const val LIBRARY_VERSION: String = "1.1.1"

    /** Value to send as the `X-Adfinia-SDK-Version` header on every request. */
    const val SDK_VERSION_HEADER: String = "$LIBRARY_NAME@$LIBRARY_VERSION"
}
