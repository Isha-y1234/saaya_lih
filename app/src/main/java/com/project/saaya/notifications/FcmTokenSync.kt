package com.project.saaya.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.EncryptedAuthSessionStore
import com.project.saaya.data.local.FcmRegistrationStore
import com.project.saaya.data.remote.DeviceTokenApiService
import com.project.saaya.data.remote.TrustedCircleDependencies
import com.project.saaya.data.repository.DeviceTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FcmTokenSync {
    private const val WORK = "saaya-fcm-token-registration"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<FcmTokenRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun localSignOut(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        FcmRegistrationStore(context).clear()
        SosNotifications.clear(context)
        WorkManager.getInstance(context).cancelAllWorkByTag(InvitationNotificationWork.TAG)
        InvitationNotifications.clear(context)
        // Keep the SDK token stable. Asynchronous deletion can race the next account's
        // registration. Backend session revocation and receiver account checks invalidate access.
    }
}

class FcmTokenRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = EncryptedAuthSessionStore(applicationContext)
        val session = store.read() ?: return Result.success()
        if (session.expiresAt <= System.currentTimeMillis() / 1000) return Result.success()
        return try {
            val token = FirebaseMessaging.getInstance().token.awaitResult()
            val api = TrustedCircleDependencies.createRetrofit(SessionManager(store)).create(DeviceTokenApiService::class.java)
            DeviceTokenRepository(api, FcmRegistrationStore(applicationContext)).register(
                token, session, SosNotifications.isEnabled(applicationContext)
            ) { store.read()?.token == session.token }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is HttpException && failure.code() in listOf(401, 403, 409)) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
