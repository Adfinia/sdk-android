// Root project — no buildscript work happens here; everything is in :sdk.

plugins {
    // Versions come from gradle/libs.versions.toml (the version catalog).
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.vanniktech.mavenpublish) apply false
    alias(libs.plugins.dokka) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
