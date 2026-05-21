# adfinia-sdk-android changelog

All notable changes to the official Adfinia Android SDK land here. Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The SDK
follows [semver](https://semver.org/) starting at 1.0.0.

## [1.0.0-rc.1] — 2026-05-22

First release candidate. The wire surface and public API are now frozen
for the 1.0 line — only backwards-compatible additions land after this.

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
- Library version bumped `0.2.0 → 1.0.0-rc.1`.
- `mavenPublishing.coordinates(...)` updated to match the
  `BuildMeta.LIBRARY_VERSION` constant.

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
