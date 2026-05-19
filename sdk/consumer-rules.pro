# Public types must not be obfuscated — consumers reference them by name.
-keep class com.adfinia.sdk.Adfinia { public *; }
-keep class com.adfinia.sdk.AdfiniaConfig { public *; }
-keep class com.adfinia.sdk.AdfiniaClient { public *; }
