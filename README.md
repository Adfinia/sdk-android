# Adfinia SDK for Android

Official Adfinia SDK for Android — first-party event + identify ingestion with offline buffering, exponential-backoff retries, and a consent gate.

Same public surface as `@adfinia/sdk-web` and `AdfiniaSDK` (iOS) — same method names, same argument shapes, same wire format.

- **minSdk:** 24 (Android 7.0+)
- **Language:** Kotlin 1.9+, Java 17 bytecode
- **Bundle size:** ~38 KB AAR (with OkHttp + Coroutines provided by the host app)
- **License:** MIT

---

## Install

### Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.adfinia:sdk-android:1.0.0")
}
```

### Gradle (Groovy)

```groovy
// app/build.gradle
dependencies {
    implementation 'com.adfinia:sdk-android:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>com.adfinia</groupId>
    <artifactId>sdk-android</artifactId>
    <version>1.0.0</version>
</dependency>
```

The SDK pulls in OkHttp 4.x, Kotlin Coroutines, and AndroidX WorkManager as transitive dependencies.

---

## Quickstart

```kotlin
import android.app.Application
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
```

Then track from anywhere:

```kotlin
Adfinia.identify("cust_42", mapOf("plan" to "growth", "country" to "AE"))

Adfinia.track(
    "Order Completed",
    mapOf(
        "order_id" to "o_123",
        "total" to 49.99,
        "currency" to "AED",
    ),
)

Adfinia.screen("Pricing")
Adfinia.alias("cust_42")
Adfinia.reset()

lifecycleScope.launch { Adfinia.flush() }
```

### Java interop

```java
import com.adfinia.sdk.Adfinia;
import com.adfinia.sdk.AdfiniaConfig;
import java.util.Map;

Adfinia.initialize(getApplicationContext(), new AdfiniaConfig(
    BuildConfig.ADFINIA_WRITE_KEY,
    "https://events.adfinia.com",
    BuildConfig.DEBUG,
    () -> ConsentStore.granted(),
    50,
    5_000L,
    1_000
));

Adfinia.identify("cust_42", Map.of("plan", "growth"));
Adfinia.track("Order Completed", Map.of("total", 49.99, "currency", "AED"));
Adfinia.flushBlocking();
```

`Adfinia.flush()` is a `suspend` function. From Java, use `Adfinia.flushBlocking()` or `kotlinx.coroutines.BuildersKt.runBlocking(...)`.

---

## API reference

| Method | Notes |
|--------|-------|
| `Adfinia.initialize(context, config)` | One-shot. Subsequent calls are logged and ignored. |
| `Adfinia.identify(customerId, traits?)` | Customer-id form. |
| `Adfinia.identify(arg, traits?)` | Sealed-class form. |
| `Adfinia.track(event, properties?)` | Event name + properties. |
| `Adfinia.screen(name?, properties?)` | Screen view. |
| `Adfinia.alias(newId, previousId?)` | Link the anonymous session to a known customer. |
| `Adfinia.reset()` | Logout — mints a new anonymous_id, clears customer_id + traits. |
| `Adfinia.flush()` | `suspend` — drains the queue. |
| `Adfinia.flushBlocking()` | Java-friendly blocking variant. |

### `AdfiniaConfig`

| Field | Default | Notes |
|-------|---------|-------|
| `writeKey` | — | Tenant write-only public key (`pk_live_…` / `pk_test_…`). |
| `host` | `https://events.adfinia.com` | Override for self-hosted ingest. |
| `debug` | `false` | Log SDK internals to `Log.d("Adfinia", …)`. |
| `consent` | `null` | Consent gate — see below. |
| `flushAt` | `50` | Number of buffered events that triggers an immediate flush. |
| `flushIntervalMs` | `5_000` | Background flush cadence. |
| `maxQueueSize` | `1_000` | Cap on buffered events; oldest dropped on overflow. |

---

## Platform targets

| Target | Minimum |
|--------|---------|
| Android API level | 24 (Android 7.0) |
| compileSdk | 34 |
| Kotlin | 1.9+ |
| JVM bytecode | 17 |
| Gradle | 8.x |

---

## Consent integration

The SDK no-ops every public method until the consent lambda returns `true`. If the consent lambda throws, the SDK treats it as no-consent (fail-closed). Once consent flips to `true`, subsequent `track()` / `identify()` calls land in the queue immediately — no replay of previously dropped events.

Full consent-architecture write-up: [docs.adfinia.com/user-guide/consent](https://docs.adfinia.com/user-guide/consent).

---

## Looking for the full integration guide?

[docs.adfinia.com/user-guide/sdk-integration#android](https://docs.adfinia.com/user-guide/sdk-integration#android) — covers ProGuard / R8, WorkManager background flushes, Play Store privacy disclosures, self-hosted ingest configuration, and the full Java interop story.

---

## Privacy disclosures

The SDK only sends data you explicitly track. It does **not**:

- Read the device Advertising ID (AAID).
- Use Google Play Services unless your host app already includes them.
- Read the clipboard, contacts, SMS, or call log.
- Access any sensor (microphone, camera, location, accelerometer).

The library manifest declares two permissions that auto-merge into your app:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## R8 / ProGuard

The SDK ships consumer rules — no extra configuration required in your `proguard-rules.pro`. The public surface (`com.adfinia.sdk.*`) is kept; the internal queue/identity classes are free to shrink.

---

## Issues + contributing

- Bugs and feature requests: [github.com/Adfinia/sdk-android/issues](https://github.com/Adfinia/sdk-android/issues)
- Contributing guide: [CONTRIBUTING.md](./CONTRIBUTING.md)
- Email: engineering@adfinia.com

---

## License

MIT — see [LICENSE](./LICENSE).
