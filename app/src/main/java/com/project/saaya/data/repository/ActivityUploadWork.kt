package com.project.saaya.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

internal object ActivityUploadWork {
    fun cancelLegacyUploads(context: Context, owner: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("activity-upload:$owner")
    }
}

/** Work persisted by an older APK can still reference this class during an upgrade. */
class ActivityUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()
}
