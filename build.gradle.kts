// Root project — no buildscript work happens here; everything is in :sdk.

plugins {
    id("com.android.library") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.vanniktech.maven.publish") version "0.28.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
