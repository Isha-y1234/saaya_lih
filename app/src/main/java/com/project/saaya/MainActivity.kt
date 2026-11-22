package com.project.saaya

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.project.saaya.data.local.UserPreferencesRepository
import com.project.saaya.navigation.SaayaNavHost
import com.project.saaya.ui.theme.SaayaTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.saaya.ui.state.AuthViewModel
import com.project.saaya.notifications.SosNotificationPayload
import com.project.saaya.notifications.InvitationNotificationPayload
import com.project.saaya.notifications.InvitationNotificationTarget

class MainActivity : ComponentActivity() {
    private val pendingInvitation = mutableStateOf<InvitationNotificationTarget?>(null)
    private val pendingSos = mutableStateOf<SosNotificationPayload?>(null)

    private fun parseInvitation(intent: Intent?): InvitationNotificationTarget? = runCatching {
        InvitationNotificationTarget.parse(listOf("type", "invitationId", "invitationAccountId").mapNotNull { key ->
            intent?.getStringExtra(key)?.let { key to it }
        }.toMap())
    }.getOrNull()

    private fun clearInvitationIntent() {
        pendingInvitation.value = null
        if (intent?.getStringExtra("type") == InvitationNotificationPayload.TYPE) {
            listOf("type", "invitationId", "invitationAccountId").forEach { intent?.removeExtra(it) }
            intent?.data = null
        }
    }

    private fun parseSos(intent: Intent?): SosNotificationPayload? = runCatching { SosNotificationPayload.parse(
        listOf("type", "incidentId", "recipientId", "title", "body", "action").mapNotNull { name ->
            intent?.getStringExtra(name)?.let { name to it }
        }.toMap()
    ) }.getOrNull()

    private fun clearSosIntent() {
        pendingSos.value = null
        listOf("type", "incidentId", "recipientId", "title", "body", "action").forEach { intent?.removeExtra(it) }
        intent?.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSos.value = parseSos(intent)
        pendingInvitation.value = parseInvitation(intent)
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        pendingSos.value = if (savedInstanceState?.getString("sosIncidentId") != null) {
            SosNotificationPayload.parse(mapOf("type" to "SOS",
                "incidentId" to savedInstanceState.getString("sosIncidentId").orEmpty(),
                "recipientId" to savedInstanceState.getString("sosRecipientId").orEmpty(),
                "action" to savedInstanceState.getString("sosAction").orEmpty()))
        } else parseSos(intent)

        pendingInvitation.value = if (parseSos(intent) != null) null else parseInvitation(intent) ?: savedInstanceState?.let {
            InvitationNotificationTarget.parse(mapOf("type" to InvitationNotificationPayload.TYPE,
                "invitationId" to it.getString("circleInvitationId").orEmpty(),
                "invitationAccountId" to it.getString("circleAccountId").orEmpty()))
        }
        // An invitation launch cannot replay an older pending SOS saved state.
        if (pendingInvitation.value != null) pendingSos.value = null

        enableEdgeToEdge()

        setContent {
            SaayaTheme {
                val repository = remember {
                    UserPreferencesRepository(
                        applicationContext
                    )
                }

                SaayaNavHost(
                    repository = repository,
                    authViewModel = viewModel(factory = AuthViewModel.Factory(applicationContext, repository)),
                    pendingSosAlert = pendingSos.value,
                    onSosAlertHandled = ::clearSosIntent,
                    pendingInvitation = pendingInvitation.value,
                    onInvitationHandled = ::clearInvitationIntent
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingSos.value?.let {
            outState.putString("sosIncidentId", it.incidentId)
            outState.putString("sosRecipientId", it.recipientId)
            outState.putString("sosAction", it.action)
        }
        pendingInvitation.value?.let {
            outState.putString("circleInvitationId", it.invitationId)
            outState.putString("circleAccountId", it.accountId)
        }
        super.onSaveInstanceState(outState)
    }
}
