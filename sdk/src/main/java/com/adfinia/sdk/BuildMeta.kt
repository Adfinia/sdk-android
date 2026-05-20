// Build-time constants. Bumped by the release script alongside the
// `mavenPublishing.coordinates(...)` version in `sdk/build.gradle.kts`.

package com.adfinia.sdk

internal object BuildMeta {
    const val LIBRARY_NAME: String = "adfinia-sdk-android"
    const val LIBRARY_VERSION: String = "0.2.0"
}
