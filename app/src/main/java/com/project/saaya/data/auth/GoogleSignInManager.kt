package com.project.saaya.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.project.saaya.BuildConfig

class GoogleSignInManager(context: Context) {
    private val credentialManager = CredentialManager.create(context.applicationContext)

    suspend fun getIdToken(activityContext: Context): String {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()

        // Temporary debug-only configuration diagnostics. Never log credentials or tokens.
        if (BuildConfig.DEBUG) {
            val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
            val clientPrefix = clientId.removeSuffix(".apps.googleusercontent.com")
            val maskedClientId = if (clientId.isBlank()) "(missing)" else
                "${clientPrefix.take(12)}…${clientPrefix.takeLast(4)}.apps.googleusercontent.com"
            Log.d(
                "SaayaGoogleAuth",
                "packageName=${activityContext.packageName}, " +
                    "applicationId=${BuildConfig.APPLICATION_ID}, buildType=${BuildConfig.BUILD_TYPE}, " +
                    "webClientIdPresent=${clientId.isNotBlank()}, webClientId=$maskedClientId"
            )
        }
        val result = credentialManager.getCredential(
            context = activityContext,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        )
        val credential = result.credential
        require(credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected sign-in credential"
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    suspend fun clearCredentialState() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
