package com.project.saaya.ui.state

import android.content.Context
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.project.saaya.data.auth.GoogleSignInManager
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.EncryptedAuthSessionStore
import com.project.saaya.data.local.UserPreferencesRepository
import com.project.saaya.data.remote.TrustedCircleDependencies
import com.project.saaya.data.remote.UserApiService
import com.project.saaya.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import com.project.saaya.notifications.FcmTokenSync

class AuthViewModel(
    private val repository: AuthRepository,
    private val google: GoogleSignInManager,
    val retrofit: Retrofit,
    private val onLocalSignOut: () -> Unit = {}
) : ViewModel() {
    val uiState = repository.state

    init { viewModelScope.launch { repository.restore() } }

    fun retryRestore() {
        if (uiState.value.isLoading) return
        repository.beginSignIn()
        viewModelScope.launch { repository.restore() }
    }

    fun refreshProfile() {
        if (uiState.value.isLoading || uiState.value.isRefreshingProfile) return
        viewModelScope.launch { repository.refreshProfile() }
    }

    fun signIn(activityContext: Context) {
        if (uiState.value.isLoading) return
        repository.beginSignIn()
        viewModelScope.launch {
            try {
                repository.signIn(google.getIdToken(activityContext))
            } catch (_: GetCredentialCancellationException) {
                repository.cancelSignIn()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign-in failed", e)
                repository.signInFailed()
            }
        }
    }

    fun signOut() {
        if (uiState.value.isLoading) return
        runCatching { onLocalSignOut() }
        repository.beginSignIn()
        viewModelScope.launch {
            try {
                repository.signOut()
            } finally {
                // Clear any notification delivered between the initial cleanup and session removal.
                runCatching { onLocalSignOut() }
            }
            try {
                google.clearCredentialState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The local and backend sessions have already been cleared.
            }
        }
    }

    class Factory(
        private val context: Context,
        private val preferences: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == AuthViewModel::class.java)
            val sessions = SessionManager(EncryptedAuthSessionStore(context.applicationContext))
            val retrofit = TrustedCircleDependencies.createRetrofit(sessions)
            val repository = AuthRepository(
                retrofit.create(UserApiService::class.java), sessions,
                bindUser = { preferences.bindAuthenticatedUser(it.id, it.name) },
                clearProfile = { preferences.clearUserData() }
            )
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(repository, GoogleSignInManager(context.applicationContext), retrofit,
                onLocalSignOut = { FcmTokenSync.localSignOut(context.applicationContext) }) as T
        }
    }
}
