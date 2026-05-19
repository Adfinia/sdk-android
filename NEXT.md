# Adfinia SDK Android — next implementation steps

| ID | Title | Notes |
|----|-------|-------|
| NEXT-AND-1 | UUIDv7 generator | Swap `UUID.randomUUID()` (v4) for a UUIDv7 implementation matching the web SDK's monotonic-counter logic. |
| NEXT-AND-2 | Real OkHttp transport | POST `{batch}` to `/api/v1/track` + `/api/v1/identify` with Bearer auth. 5xx → retry, 4xx → drop. |
| NEXT-AND-3 | SharedPreferences persistence | File `adfinia_sdk`. Keys `adfinia.identity` + `adfinia.queue`. Survive cold-start. |
| NEXT-AND-4 | Room queue | Replace the in-memory `ArrayDeque` with a Room table once buffer exceeds ~100 events; SharedPreferences is fine for the steady-state case. |
| NEXT-AND-5 | Exponential backoff scheduler | `WorkManager` periodic worker for background flush. |
| NEXT-AND-6 | Lifecycle integration | Use `ProcessLifecycleOwner` to flush on app background. |
| NEXT-AND-7 | JUnit / Robolectric coverage to parity with web | Mirror the web SDK's `client.test.ts` + `queue.test.ts` + `identity.test.ts`. |
| NEXT-AND-8 | Example app | A minimal Compose app under `examples/compose-demo/` exercising the full surface. |
| NEXT-AND-9 | Maven Central publishing pipeline | Configure `MAVEN_USERNAME` / `MAVEN_PASSWORD` secrets + Sonatype OSSRH staging release. |
