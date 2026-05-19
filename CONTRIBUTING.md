# Contributing to Adfinia SDK Android

Public API parity with `@adfinia/sdk-web` is the hard rule — adding a
method here means filing parallel issues against the web, iOS, RN, and
Flutter SDKs.

## Local setup

```bash
git clone https://github.com/infinia-net/adfinia-android-sdk
cd adfinia-android-sdk
./gradlew :sdk:assembleDebug
./gradlew :sdk:testDebugUnitTest
```

Or open the root in Android Studio (Iguana+).

## Style

- Kotlin 1.9+, K2 compiler when stable.
- Public types under `com.adfinia.sdk`. Internal helpers stay `internal`.
- `@JvmStatic` + `@JvmOverloads` on every public method so Java consumers
  get sensible call sites.
- JUnit 4 + Robolectric for unit tests. Espresso + AndroidJUnitRunner for
  instrumentation.

## Security

Email `security@adfinia.com`.
