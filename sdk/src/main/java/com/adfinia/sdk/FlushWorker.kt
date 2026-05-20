// FlushWorker — WorkManager-driven background flush. Runs every 15 minutes
// (the minimum periodic interval WorkManager supports) so events queued
// while the host app is in the background still drain on schedule, even
// after the process has been killed.
//
// We don't ship our own retry curve here — the queue itself does
// exponential backoff per attempt; WorkManager just keeps us alive across
// process death and battery-saver. WorkManager's own retry policy stays at
// its defaults (linear, 10s start), since each invocation is a fast flush.
//
// Constraints: only run when the network is available; otherwise we'd
// burn battery on a guaranteed failure.

package com.adfinia.sdk

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class FlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        Adfinia.flush()
        Result.success()
    } catch (_: Throwable) {
        // The queue retains buffered events on failure, so a Result.retry()
        // would double-retry. Just bail and let the next scheduled run pick
        // up where we left off.
        Result.success()
    }

    companion object {
        const val WORK_NAME = "com.adfinia.sdk.flush"

        internal fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<FlushWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
