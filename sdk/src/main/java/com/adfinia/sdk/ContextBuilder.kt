// Per-event context block — OS / device / app metadata that the API uses
// for analytics segmentation. Built once at SDK init and re-used on every
// payload so we don't hit the package manager / display metrics per event.

package com.adfinia.sdk

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import java.util.Locale
import java.util.TimeZone

internal object ContextBuilder {

    fun build(appContext: Context?): AdfiniaContext {
        if (appContext == null) return minimalContext()
        return try {
            val pm = appContext.packageManager
            val packageName = appContext.packageName
            val pi = pm.getPackageInfo(packageName, 0)
            val applicationLabel = pm.getApplicationLabel(appContext.applicationInfo)?.toString()
            val metrics: DisplayMetrics = appContext.resources.displayMetrics
            AdfiniaContext(
                libraryName = BuildMeta.LIBRARY_NAME,
                libraryVersion = BuildMeta.LIBRARY_VERSION,
                locale = currentLocale(),
                timezone = TimeZone.getDefault().id,
                osName = "Android",
                osVersion = Build.VERSION.RELEASE,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                appName = applicationLabel,
                appVersion = pi?.versionName,
                appBuild = versionCode(pi),
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                networkType = null, // ConnectivityManager check skipped — needs perm in some envs.
            )
        } catch (_: Throwable) {
            minimalContext()
        }
    }

    private fun minimalContext(): AdfiniaContext = AdfiniaContext(
        libraryName = BuildMeta.LIBRARY_NAME,
        libraryVersion = BuildMeta.LIBRARY_VERSION,
        locale = currentLocale(),
        timezone = TimeZone.getDefault().id,
    )

    private fun currentLocale(): String {
        val loc = Locale.getDefault()
        val tag = try { loc.toLanguageTag() } catch (_: Throwable) { null }
        return tag ?: "${loc.language}-${loc.country}".trimEnd('-')
    }

    @Suppress("DEPRECATION")
    private fun versionCode(pi: android.content.pm.PackageInfo?): String? {
        if (pi == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toString()
        } else {
            pi.versionCode.toString()
        }
    }
}
