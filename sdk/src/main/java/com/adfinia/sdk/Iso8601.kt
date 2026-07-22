// Small ISO 8601 helper. We don't depend on `java.time` because Adfinia
// targets minSdk 24, which has only partial desugaring without the
// coreLibraryDesugaring opt-in. SimpleDateFormat is dependency-free.

package com.adfinia.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Iso8601 {
    // ThreadLocal.withInitial requires API 26; minSdk is 24, so use the
    // API-24-safe initialValue() override instead (lint NewApi fix, v1.1.1).
    private val formatter: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    fun format(epochMs: Long): String = formatter.get().format(Date(epochMs))
}
