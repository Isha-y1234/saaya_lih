package com.project.saaya.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.project.saaya.MainActivity
import com.project.saaya.data.local.EncryptedAuthSessionStore
import com.project.saaya.data.remote.NotificationApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SosActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_DISMISS = "com.project.saaya.ACTION_SOS_DISMISS"
        const val EXTRA_INCIDENT_ID = "incidentId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val incidentId = intent.getStringExtra(EXTRA_INCIDENT_ID) ?: return
        
        when (intent.action) {
            ACTION_DISMISS -> {
                NotificationManagerCompat.from(context).cancel("saaya-sos:$incidentId", 1001)
                recordDismissal(context, incidentId)
            }
        }
    }

    private fun recordDismissal(context: Context, incidentId: String) {
        val session = EncryptedAuthSessionStore(context).read() ?: return
        // Use a simple temporary retrofit for the background action if the main one isn't accessible
        // In a production app, use Dependency Injection (Hilt/Koin)
        scope.launch {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl("https://saaya-api.render.com/") // Base URL from project context
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                val api = retrofit.create(NotificationApiService::class.java)
                api.dismissAlert(incidentId)
            } catch (e: Exception) {
                // Silently fail or retry with WorkManager
            }
        }
    }
}
