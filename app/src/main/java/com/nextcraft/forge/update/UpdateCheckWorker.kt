package com.nextcraft.forge.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.nextcraft.forge.data.AppPreferences
import java.util.concurrent.TimeUnit

/** Checks for a newer build in the background and announces it; errors (no network, no release) just wait for the next round. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!AppPreferences(applicationContext).updateNotifications) return Result.success()
        val info = runCatching { AppUpdater(applicationContext).check() }.getOrNull() ?: return Result.success()
        UpdateNotifier.notifyIfNew(applicationContext, info)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "app-update-check"

        /** Every 6 hours while the network is up; the same request is kept when scheduled again. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
