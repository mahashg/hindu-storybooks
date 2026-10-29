package com.mahesh.hindustorybooks.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mahesh.hindustorybooks.data.CatalogRefresh

/**
 * Daily catalog refresh. Runs the same validated flow as the manual button.
 * Silent: when the app is not open there is no toast; the new arrivals and
 * their नया tags are visible on the next launch.
 */
class CatalogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            CatalogRefresh.refreshBlocking(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }
}
