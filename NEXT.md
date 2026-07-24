# Adfinia SDK Android — next implementation steps

## Round 4 status (2026-07-24) — push + inbox (v1.2.0)

Push registration + the in-app notification inbox shipped this round,
mirroring the React Native SDK contract. All covered by JVM JUnit tests
(`:sdk:testReleaseUnitTest` green: 16 new tests across push + inbox).

| ID | Title | Status |
|----|-------|--------|
| NEXT-AND-10 | Push registration — `registerForPush()`/`unregisterForPush()`/`onNewPushToken()`; FCM token via optional `compileOnly firebase-messaging` (reflection) or caller-supplied; POST `/push/register` with RN wire shape; emits `push_registered` | **DONE** |
| NEXT-AND-11 | In-app inbox client — `Adfinia.notifications.list/markRead/markAllRead` + live SSE `stream()` (okhttp-sse); typed `InboxNotification` models | **DONE** |

### 🔴 Backend follow-up (filed for the api agent — NOT fixed here)

`POST /api/v1/push/register` is served by the push activation `Provider`
(`Dispatcher.RegisterDevice` / `FCMProvider.RegisterDevice`), which **logs
only and does not persist to `device_tokens`** ("persistence deferred" /
"persistence handled by upstream dispatcher"). Only `POST /api/v1/device-tokens`
writes that table, and `device_tokens` is the fan-out source
(`ListActiveTokensForContact`). So tokens registered via `/push/register` never
receive fan-out pushes.

Additional contract mismatch on the api side: the Go `RegisterRequest` struct
requires `contact_id` and rejects unknown fields (`DisallowUnknownFields`),
whereas the OpenAPI spec + the RN/Android SDK send `{token, platform, device_id,
app_version, customer_id/external_id/anonymous_id}` (no `contact_id`). The api
handler should either point `/push/register` at the `device_tokens` repository
(resolving the contact from the identity bag) or the SDKs should be redirected
to `/device-tokens`. Decision is the api agent's; this SDK mirrors RN as-is.

## Round 2 status (2026-05-20)

The skeleton has been replaced with real implementations. Everything below
marked DONE shipped in this round and is covered by JUnit tests on the JVM
test runner; the CI build (`./gradlew :sdk:testReleaseUnitTest`) is the
single source of truth.

| ID | Title | Status |
|----|-------|--------|
| NEXT-AND-1 | UUIDv7 generator with 12-bit monotonic counter (RFC 9562 §6.2) | **DONE** |
| NEXT-AND-2 | Real OkHttp transport with shared client + 4xx-drop / 5xx-retry semantics | **DONE** |
| NEXT-AND-3 | SharedPreferences persistence for `adfinia.identity` + `adfinia.queue` | **DONE** |
| NEXT-AND-4 | Persistent on-disk queue with batching + drop-on-overflow + envelope codec | **DONE** (SharedPreferences-backed; Room is overkill at expected volumes) |
| NEXT-AND-5 | Exponential backoff scheduler (1s → 30s cap, reset on success) | **DONE** |
| NEXT-AND-5b | WorkManager periodic background flush (`com.adfinia.sdk.flush`, 15min, network-constrained) | **DONE** |
| NEXT-AND-6 | Lifecycle integration — flush on app background | NEXT — `ProcessLifecycleOwner.get().lifecycle.addObserver(...)` from the SDK init |
| NEXT-AND-7 | JUnit coverage to parity with web SDK (58 tests across uuid / identity / queue / transport / client / codec / facade) | **DONE** |
| NEXT-AND-8 | Example Compose app under `examples/compose-demo/` | NEXT — demo Activity exercising identify → screen → track → reset |
| NEXT-AND-9 | Maven Central publishing pipeline | BLOCKED on Sonatype OSSRH org provisioning — see `product/notes-from-sdks.md` |

## Carry-over items (Round 3)

- **NEXT-AND-6:** `ProcessLifecycleOwner` integration so the SDK fires an
  immediate `flush()` when the app backgrounds, in addition to the periodic
  WorkManager pass.
- **NEXT-AND-8:** `examples/compose-demo/` standalone Compose app that
  exercises the full surface against a local mock server.
- **NEXT-AND-9:** Sonatype OSSRH staging + GPG signing once the org account
  is provisioned. The `mavenPublishing` block in `sdk/build.gradle.kts` is
  ready — only the secrets are missing.
- **Identify response handling:** the `/api/v1/identify` endpoint returns
  `{ customer_id, created }`. Cache the returned `customer_id` and replay it
  on subsequent calls (currently we send whatever the caller supplied).
- **Optional Encrypted SharedPreferences** for tenants that ask for
  at-rest encryption of the identity blob.
