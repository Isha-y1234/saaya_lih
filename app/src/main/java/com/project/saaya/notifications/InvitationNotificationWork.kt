package com.project.saaya.notifications

import android.content.Context
import androidx.work.*
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.local.EncryptedAuthSessionStore
import com.project.saaya.data.remote.SaayaApiService
import com.project.saaya.data.remote.TrustedCircleDependencies
import com.project.saaya.data.repository.FastApiTrustedContactJsonMapper
import com.project.saaya.data.repository.TrustedCircleRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/** Persist the hint, then verify with the existing authenticated incoming API off the FCM callback. */
object InvitationNotificationWork {
    const val TAG = "saaya-invitation-notification"
    fun enqueue(context: Context, payload: InvitationNotificationPayload) {
        val session = EncryptedAuthSessionStore(context).read() ?: return
        if (session.expiresAt <= System.currentTimeMillis() / 1000) return
        val request = OneTimeWorkRequestBuilder<InvitationNotificationWorker>()
            .setInputData(workDataOf("invitationId" to payload.invitationId, "accountId" to session.user.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$TAG:${session.user.id}:${payload.invitationId}", ExistingWorkPolicy.KEEP, request)
    }
}

class InvitationNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val payload = InvitationNotificationPayload.parse(mapOf("type" to InvitationNotificationPayload.TYPE,
            "invitationId" to inputData.getString("invitationId").orEmpty())) ?: return Result.success()
        val store = EncryptedAuthSessionStore(applicationContext)
        val session = store.read() ?: return Result.success()
        if (session.user.id != inputData.getString("accountId") ||
            session.expiresAt <= System.currentTimeMillis() / 1000) return Result.success()
        return try {
            val api = TrustedCircleDependencies.createRetrofit(invitationSessionManager(session)).create(SaayaApiService::class.java)
            val incoming = TrustedCircleRepository(api, FastApiTrustedContactJsonMapper, session.user.id).loadIncomingInvitations()
            payload.verifiedTargetFor(session, store.read(), incoming, System.currentTimeMillis() / 1000)?.let {
                InvitationNotifications.show(applicationContext, it)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is HttpException && failure.code() in listOf(401, 403, 404)) Result.success()
            else if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Activate a snapshot for AuthInterceptor without rewriting/clearing the real account store. */
internal fun invitationSessionManager(session: AuthSession): SessionManager {
    val snapshot = object : AuthSessionStore {
        private var value: AuthSession? = session
        override fun read(): AuthSession? = value
        override fun write(session: AuthSession) { value = session }
        override fun clear() { value = null }
    }
    return SessionManager(snapshot).apply { activate(session) }
}
