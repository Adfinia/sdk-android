# Adfinia SDK for Android

Official Adfinia SDK for Android. Same public surface as
`@adfinia/sdk-web` — same method names, same arg shapes, same wire format.

> **Status:** skeleton. The public API is stable and matches the web SDK
> shape. Network transport, SharedPreferences persistence, and exponential
> backoff are stubbed and tracked in [`NEXT.md`](./NEXT.md). Do not ship
> to production yet.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.adfinia:sdk-android:0.1.0")
}
```

Maven (legacy):

```xml
<dependency>
    <groupId>com.adfinia</groupId>
    <artifactId>sdk-android</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quickstart

```kotlin
import com.adfinia.sdk.Adfinia
import com.adfinia.sdk.AdfiniaConfig
import com.adfinia.sdk.AdfiniaConsent

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Adfinia.initialize(
            this,
            AdfiniaConfig(
                writeKey = BuildConfig.ADFINIA_WRITE_KEY,
                debug = BuildConfig.DEBUG,
                consent = AdfiniaConsent { ConsentStore.analyticsGranted() }
            )
        )
    }
}

// Identify
Adfinia.identify("cust_42", mapOf("plan" to "growth"))

// Track
Adfinia.track("Order Completed", mapOf(
    "order_id" to "o_123",
    "total" to 49.99,
    "currency" to "AED",
))

// Screen
Adfinia.screen("Pricing")

// Alias on signup
Adfinia.alias("cust_42")

// Reset on logout
Adfinia.reset()

// Optional explicit flush (suspend — call from a coroutine)
lifecycleScope.launch { Adfinia.flush() }
```

### Java interop

Every method is exposed with `@JvmStatic` + `@JvmOverloads`, so the Java
call sites are identical:

```java
Adfinia.identify("cust_42");
Adfinia.track("Order Completed", Map.of("total", 49.99));
```

`flush()` is suspending — call it from a `kotlinx.coroutines` scope or use
`BuildersKt.runBlocking` from Java.

## API surface

| Method | Notes |
|--------|-------|
| `Adfinia.initialize(context, config)` | One-shot. Subsequent calls ignored. |
| `Adfinia.identify(customerId, traits?)` | Customer-id form. |
| `Adfinia.identify(arg, traits?)` | Sealed-class form. |
| `Adfinia.track(event, properties?)` | Event name + props. |
| `Adfinia.screen(name?, properties?)` | Mobile analogue of `page()`. |
| `Adfinia.alias(newId, previousId?)` | Link anonymous → known. |
| `Adfinia.reset()` | Logout — mints new anonymous_id. |
| `Adfinia.flush()` | `suspend`, drains the in-memory buffer. |

## Privacy disclosures

The SDK only sends data you explicitly track. It does **not**:

- Read the device Advertising ID.
- Use Play Services unless your host app includes it.
- Read clipboard / contacts / SMS.

It does request `INTERNET` + `ACCESS_NETWORK_STATE` permissions via the
library manifest — these merge into your app's manifest automatically.

## Platform support

- minSdk: 24 (Android 7.0)
- targetSdk / compileSdk: 34
- Kotlin: 1.9+
- Java: 17 bytecode

## License

MIT — see [LICENSE](./LICENSE).
