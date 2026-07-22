# adfinia-sdk-android changelog

All notable changes to the official Adfinia Android SDK land here. Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The SDK
follows [semver](https://semver.org/) starting at 1.0.0.

## [1.1.1] — 2026-07-22

### Fixed
- **`NewApi` error / potential runtime crash on API 24-25.** `Iso8601` used
  `ThreadLocal.withInitial`, which requires API 26 while `minSdk` is 24 — on
  Android 24-25 devices this would throw `NoSuchMethodError` at runtime.
  Replaced with an API-24-safe `ThreadLocal` subclass overriding
  `initialValue()`. Caught by Android lint (CI green again).
- Suppressed the `UNUSED_PARAMETER` warnings on the deprecated `alias()` no-op
  (parameters retained for signature compatibility).

## [1.1.0] — 2026-07-22

### Deprecated
- **`alias()`** is deprecated and is now a no-op. There is no server-side
  handler for alias/previous_id (the backend only processes track + identify),
  so the call never did anything useful and misled integrators. Anonymous-to-
  known promotion already happens automatically inside `identify()`, which
  ships the live `anonymous_id` alongside the `customer_id`. Both
  `Adfinia.alias(...)` and `AdfiniaClient.alias(...)` now carry Kotlin
  `@Deprecated` annotations (with a `ReplaceWith("identify(...)")` quick-fix)
  and enqueue/transmit nothing; the first call emits a one-time deprecation log
  (debug-gated, same channel as other SDK internals). The method signature is
  unchanged, so existing callers keep compiling. Migrate to `identify()`.

### Removed
- Internal `AdfiniaPayloadType.ALIAS` and its wire/synthesise/`previous_id`
  merge branches in `PayloadCodec`. No `$alias` event can be produced, and a
  persisted `alias` envelope from an older build now decodes to null (dropped).
  No public-API impact.

## [1.0.1] — 2026-07-01

### Fixed
- Default ingest `host` is now `https://api.adfinia.com` (was
  `https://events.adfinia.com`, an unprovisioned domain that failed at DNS so
  events were dropped silently). Callers passing an explicit `host` are
  unaffected. Brings Android in line with web and React Native.

### Changed
- Transport now ALWAYS batches: even a single event ships as a 1-element
  `{"events":[...]}` to `POST /api/v1/{track,identify}/batch`. The single-event
  endpoints are no longer used. The single `POST /api/v1/track` path does not
  stamp the event environment from the authenticating API key (it defaults to
  `live`), so a `adf_test_` key's solo event would be mis-tagged and leak into
  live analytics; the batch endpoint stamps it from the key. Mirrors
  `@adfinia/sdk-web` 1.3.1 + `@adfinia/sdk-react-native` 1.0.1. Folds into the
  first published release (1.0.0 is not yet on Maven Central).

## [1.0.0] — 2026-05-22

First stable release. Same content as the dev-internal-only
`1.0.0-rc.1` build (never published to Maven Central, never tagged on
GitHub); the founder direction on 2026-05-22 was to drop the `-rc.1`
suffix and ship straight as `1.0.0`.

### Added
- **Server-driven runtime config.** On `Adfinia.initialize()`, the SDK
  fires a background coroutine that GETs `/api/v1/sdk/config` and
  applies `batch_size` + `flush_interval_ms` to the running
  `EventQueue`. Soft-fails on every error — local defaults stay.
  Unknown response fields are ignored (forward-compat).
- **`X-Adfinia-SDK-Version` header.** Every request (batch + single +
  `/sdk/config`) carries `adfinia-sdk-android@<version>`. The server's
  version middleware returns `426 Upgrade Required` once this release
  falls below the supported floor.
- **`EventQueue.applyRemoteConfig(...)`** — internal API the client
  uses to apply remote knobs without restart.
- **Gradle wrapper.** `gradlew`, `gradlew.bat`, and
  `gradle/wrapper/gradle-wrapper.{jar,properties}` are committed and
  pinned to Gradle 8.5 so consumers and CI run the same build.
- **`BuildMeta.SDK_VERSION_HEADER`** — single source of truth for the
  HTTP header value.

### Changed
- Library version bumped `0.2.0 → 1.0.0`.
- `mavenPublishing.coordinates(...)` updated to match the
  `BuildMeta.LIBRARY_VERSION` constant.

## ~~[1.0.0-rc.1] — 2026-05-22~~

~~Dev-internal release candidate. Never published to Maven Central,
never tagged on GitHub; superseded by `1.0.0` on the same day per
founder direction. Same code, no `-rc.1` suffix on the public artifact.~~

### Notes
- Maven Central publish is configured but NOT triggered by this commit
  — the `com.vanniktech.maven.publish` plugin needs signing + Sonatype
  credentials supplied at release time. Local `./gradlew publishToMavenLocal`
  works without secrets.
- Endpoints in use:
  - `POST /api/v1/track/batch`
  - `POST /api/v1/identify/batch`
  - `POST /api/v1/track` and `/api/v1/identify` (single-event fallback)
  - `GET /api/v1/sdk/config` (init)

## [0.2.0] — 2026-05-20

Initial Android library alongside the web / iOS / React Native /
Flutter SDK skeletons.
