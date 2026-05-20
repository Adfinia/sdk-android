# Adfinia SDK for Android

Official Adfinia SDK for Android — first-party event + identify ingestion
with offline buffering, exponential-backoff retries, and a consent gate.

Same public surface as `@adfinia/sdk-web` and `AdfiniaSDK` (iOS) — same
method names, same argument shapes, same wire format.

- **minSdk:** 24 (Android 7.0+)
- **Language:** Kotlin 1.9+, Java 17 bytecode
- **Bundle size:** ~38 KB AAR (with OkHttp + Coroutines provided by the host app)
- **License:** MIT

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
    implementation("com.adfinia:sdk-android:0.2.0")
}
```

### Gradle (Groovy)

```groovy
// app/build.gradle
dependencies {
    implementation 'com.adfinia:sdk-android:0.2.0'
}
```

### Maven

```xml
<dependency>
    <groupId>com.adfinia</groupId>
    <artifactId>sdk-android</artifactId>
    <version>0.2.0</version>
</dependency>
```

The SDK pulls in OkHttp 4.x, Kotlin Coroutines, and AndroidX WorkManager as
transitive dependencies. If your app already depends on them, the
dependency-resolution rules dedupe automatically.

## Quickstart

Initialise once at app startup:

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
                // Only send when the user has consented.
                consent = AdfiniaConsent { ConsentStore.analyticsGranted() }
            )
        )
    }
}
```

Then track from anywhere:

```kotlin
// Identify the customer (after login).
Adfinia.identify("cust_42", mapOf("plan" to "growth", "country" to "AE"))

// Track behavioural events.
Adfinia.track(
    "Order Completed",
    mapOf(
        "order_id" to "o_123",
        "total" to 49.99,
        "currency" to "AED",
        "items" to listOf(
            mapOf("sku" to "p_001", "qty" to 2),
            mapOf("sku" to "p_002", "qty" to 1),
        ),
    ),
)

// Screen view (mobile analogue of page()).
Adfinia.screen("Pricing")

// Alias an anonymous session to a known customer on signup.
Adfinia.alias("cust_42")

// Logout — mints a fresh anonymous_id.
Adfinia.reset()

// Optional explicit flush — drains the buffered queue immediately.
lifecycleScope.launch { Adfinia.flush() }
```

### Java interop

Every Kotlin method is exposed with `@JvmStatic` + `@JvmOverloads`, so the
Java call sites are clean:

```java
import com.adfinia.sdk.Adfinia;
import com.adfinia.sdk.AdfiniaConfig;
import java.util.Map;

Adfinia.initialize(getApplicationContext(), new AdfiniaConfig(
    /* writeKey       */ BuildConfig.ADFINIA_WRITE_KEY,
    /* host           */ "https://events.adfinia.com",
    /* debug          */ BuildConfig.DEBUG,
    /* consent        */ () -> ConsentStore.granted(),
    /* flushAt        */ 50,
    /* flushIntervalMs*/ 5_000L,
    /* maxQueueSize   */ 1_000
));

Adfinia.identify("cust_42", Map.of("plan", "growth"));
Adfinia.track("Order Completed", Map.of("total", 49.99, "currency", "AED"));
Adfinia.flushBlocking(); // blocking flush — call from a background thread
```

`Adfinia.flush()` is a `suspend` function. From Java, use
`Adfinia.flushBlocking()` or `kotlinx.coroutines.BuildersKt.runBlocking(...)`.

## Public API

| Method | Notes |
|--------|-------|
| `Adfinia.initialize(context, config)` | One-shot. Subsequent calls are logged and ignored. |
| `Adfinia.identify(customerId, traits?)` | Customer-id form. |
| `Adfinia.identify(arg, traits?)` | Sealed-class form (object with `customerId` / `anonymousId` / `traits`). |
| `Adfinia.track(event, properties?)` | Event name + properties. |
| `Adfinia.screen(name?, properties?)` | Screen view. |
| `Adfinia.alias(newId, previousId?)` | Link the anonymous session to a known customer. |
| `Adfinia.reset()` | Logout — mints a new anonymous_id, clears customer_id + traits. |
| `Adfinia.flush()` | `suspend` — drains the queue. |
| `Adfinia.flushBlocking()` | Java-friendly blocking variant. |

### `AdfiniaConfig` reference

| Field | Default | Notes |
|-------|---------|-------|
| `writeKey` | — | Tenant write-only public key (`pk_live_…` / `pk_test_…`). |
| `host` | `https://events.adfinia.com` | Override for self-hosted ingest. |
| `debug` | `false` | Log SDK internals to `Log.d("Adfinia", …)`. |
| `consent` | `null` | Consent gate — see below. |
| `flushAt` | `50` | Number of buffered events that triggers an immediate flush. |
| `flushIntervalMs` | `5_000` | Background flush cadence. |
| `maxQueueSize` | `1_000` | Cap on buffered events; oldest dropped on overflow. |

## How it works

### Identity

- On first run, the SDK mints a `anonymous_id` as a **UUIDv7** with a 12-bit
  monotonic counter (RFC 9562 §6.2). IDs sort consistently across web / iOS /
  Android events.
- The identity persists in a private `SharedPreferences` file (`adfinia_sdk`)
  and survives cold-starts.
- `identify(customerId, …)` attaches the customer-id and merges traits.
- `reset()` clears the customer-id, drops traits, and mints a new anonymous_id.

### Batching, retry, and offline buffer

- Events buffer in memory + persist to `SharedPreferences` as they arrive, so a
  crash immediately after `track()` doesn't lose them.
- A flush fires whenever **50 events buffer up OR 5 seconds elapse** —
  whichever comes first (configurable).
- The SDK runs a single shared `OkHttpClient` and fans the batch out to
  `/api/v1/identify` + `/api/v1/track`. When the bulk endpoint lands, the
  transport will switch transparently.
- **5xx and network failures** trigger exponential-backoff retries (1s → 2s →
  4s … capped at 30s, reset on next success). Events stay buffered.
- **4xx responses** drop the offending events with a `Log.d` (debug mode only)
  — no infinite retry on bad payloads.
- A `WorkManager` periodic worker (every 15 minutes, network-constrained)
  ensures the queue still drains when the host app is in the background or
  has been killed since the last successful flush.

### Consent gate

The SDK no-ops every public method until the consent lambda returns `true`:

```kotlin
Adfinia.initialize(
    this,
    AdfiniaConfig(
        writeKey = BuildConfig.ADFINIA_WRITE_KEY,
        consent = AdfiniaConsent { ConsentStore.analyticsGranted() },
    ),
)
```

If the consent lambda throws, the SDK treats it as no-consent (fail-closed).

Once consent flips to `true`, subsequent `track()` / `identify()` calls land
in the queue immediately — no replay of previously dropped events (those
never entered the queue).

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

## R8 / ProGuard

The SDK ships consumer rules — no extra configuration required in your
`proguard-rules.pro`. The public surface (`com.adfinia.sdk.*`) is kept; the
internal queue/identity classes are free to shrink.

If you build the SDK from source and observe missing-class errors in release
builds, ensure the consumer-rules file is on the classpath:

```kotlin
android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}
```

## Background processing

The SDK registers a single unique `PeriodicWorkRequest` (`com.adfinia.sdk.flush`)
on `Adfinia.initialize(...)`. It runs every 15 minutes (the minimum cadence
WorkManager supports), only when the network is `CONNECTED`. The worker
just calls `Adfinia.flush()` — no extra batching logic, since the in-process
queue already handles batching + backoff.

If your app uses a custom `WorkManager.Configuration`, no changes are needed —
the SDK uses the default factory.

## Self-hosted ingest

Override the `host` field to point at your own ingress (Adfinia sovereign /
self-hosted deployments):

```kotlin
AdfiniaConfig(
    writeKey = BuildConfig.ADFINIA_WRITE_KEY,
    host = "https://ingest.acme.adfinia.io",
)
```

The host should respond to `POST /api/v1/identify` and `POST /api/v1/track`
with the Adfinia ingest contract — see
[`api/api/openapi.yaml`](../../api/api/openapi.yaml).

## Versioning + support

- We follow semver. Anything under `internal` (Kotlin visibility) or methods
  prefixed `_` is not part of the public contract.
- Bug reports: [github.com/infinia-net/adfinia-android-sdk/issues](https://github.com/infinia-net/adfinia-android-sdk/issues).

## License

MIT — see [LICENSE](./LICENSE).
