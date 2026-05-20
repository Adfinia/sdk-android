# Adfinia SDK — consumer ProGuard / R8 rules.
#
# Keep the public surface so reflection-based callers (Java interop,
# DI containers, etc.) keep working in R8-optimised release builds.

-keep class com.adfinia.sdk.Adfinia { *; }
-keep class com.adfinia.sdk.AdfiniaClient { *; }
-keep class com.adfinia.sdk.AdfiniaConfig { *; }
-keep class com.adfinia.sdk.AdfiniaConsent { *; }
-keep class com.adfinia.sdk.AdfiniaIdentifyArg { *; }
-keep class com.adfinia.sdk.AdfiniaIdentifyArg$* { *; }
-keep class com.adfinia.sdk.AdfiniaPayloadType { *; }
-keep class com.adfinia.sdk.FlushWorker { *; }
-keep class com.adfinia.sdk.OkHttpTransport { *; }
-keep class com.adfinia.sdk.AdfiniaTransport { *; }
-keep class com.adfinia.sdk.AdfiniaKVStore { *; }
-keep class com.adfinia.sdk.AdfiniaMemoryStore { *; }

# OkHttp's own consumer rules cover the transport — no extras needed.
