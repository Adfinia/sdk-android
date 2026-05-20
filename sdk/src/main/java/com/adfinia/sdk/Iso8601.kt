// Small ISO 8601 helper. We don't depend on `java.time` because Adfinia
// targets minSdk 24, which has only partial desugaring without the
// coreLibraryDesugaring opt-in. SimpleDateFormat is dependency-free.

package com.adfinia.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Iso8601 {
    private val formatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun format(epochMs: Long): String = formatter.get().format(Date(epochMs))
}
