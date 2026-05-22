plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.adfinia.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }

    testOptions {
        // The whole SDK is JVM-friendly — `Log` calls are wrapped in
        // try/catch so unit tests run without Robolectric.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Android's runtime ships `org.json`; pull it in explicitly for JVM
    // unit tests so the same codec paths exercise off-device.
    testImplementation("org.json:json:20240303")
}

mavenPublishing {
    coordinates("com.adfinia", "sdk-android", "1.0.0")

    pom {
        name.set("Adfinia SDK for Android")
        description.set("Official Adfinia SDK — first-party event + identify ingestion.")
        url.set("https://github.com/Adfinia/sdk-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        scm {
            url.set("https://github.com/Adfinia/sdk-android")
            connection.set("scm:git:git://github.com/Adfinia/sdk-android.git")
            developerConnection.set("scm:git:ssh://github.com/Adfinia/sdk-android.git")
        }
        developers {
            developer {
                id.set("adfinia")
                name.set("Adfinia (New Emerging Technologies)")
                email.set("engineering@adfinia.com")
            }
        }
    }
}
