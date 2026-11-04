package com.jijilabsdigital.swell

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Fallback when the foreground service is off or was killed: every ~15 minutes start Tor,
 * fetch the mailbox, and let Android stop the process again later.
 */
class PollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Swell.init(applicationContext)
        if (Swell.account == null) return Result.success()
        Swell.connect()
        val deadline = System.currentTimeMillis() + 120_000
        while (Swell.net.value != Net.Online && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        if (Swell.net.value != Net.Online) return Result.retry()
        Swell.pollNow()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("swell-poll", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
