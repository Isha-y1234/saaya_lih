package com.project.saaya.data.repository

import android.util.Log
import com.project.saaya.BuildConfig
import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.remote.GoogleSignInRequest
import com.project.saaya.data.remote.UserApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import retrofit2.Response

data class AuthState(
    val isLoading: Boolean = true,
    val user: AuthenticatedUser? = null,
    val error: String? = null,
    val canRetryRestore: Boolean = false,
    val isRefreshingProfile: Boolean = false,
    val profileError: String? = null
)

class AuthRepository(
    private val api: UserApiService,
    private val sessions: SessionManager,
    private val bindUser: suspend (AuthenticatedUser) -> Unit,
    private val clearProfile: suspend () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val mutableState = MutableStateFlow(AuthState())
    val state = mutableState.asStateFlow()

    init {
        sessions.onInvalidated = {
            mutableState.value = AuthState(isLoading = false, error = "Your session expired. Sign in again.")
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        mutableState.value = AuthState()
        try {
            val saved = sessions.saved()
            if (saved == null || saved.expiresAt <= nowSeconds()) {
                sessions.clear()
                mutableState.value = AuthState(isLoading = false)
                return@withContext
            }
            val user = api.getMe("Bearer ${saved.token}").bodyOrThrow()
            require(user.id == saved.user.id) { "Session identity mismatch" }
            activate(AuthSession(saved.token, user, saved.expiresAt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is HttpException && failure.code() == 401 || failure is IllegalArgumentException) {
                sessions.clear()
                mutableState.value = AuthState(isLoading = false, error = "Please sign in again.")
            } else {
                // A network outage must not erase a valid persisted session or grant offline access.
                mutableState.value = AuthState(isLoading = false,
                    error = "Could not restore your session. Check your connection and retry.", canRetryRestore = true)
            }
        }
    }

    fun beginSignIn() { mutableState.value = AuthState() }

    /** Refresh display data only; never replace a newer account/session or change SOS preferences. */
    suspend fun refreshProfile() {
        val session = sessions.current ?: return
        mutableState.update { state ->
            if (state.user?.id == session.user.id) state.copy(isRefreshingProfile = true, profileError = null) else state
        }
        try {
            val user = api.getMe("Bearer ${session.token}").bodyOrThrow()
            require(user.id == session.user.id) { "Session identity mismatch" }
            mutableState.update { state ->
                if (sessions.current?.token == session.token && state.user?.id == session.user.id)
                    state.copy(user = user, profileError = if (user.saayaId.isNullOrBlank())
                        "Your Saaya ID is unavailable. Please retry." else null)
                else state
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { state ->
                if (sessions.current?.token == session.token && state.user?.id == session.user.id)
                    state.copy(profileError = "Could not load your Saaya ID. Check your connection and retry.")
                else state
            }
        } finally {
            mutableState.update { state ->
                if (sessions.current?.token == session.token && state.user?.id == session.user.id)
                    state.copy(isRefreshingProfile = false) else state
            }
        }
    }
    fun cancelSignIn() { mutableState.value = AuthState(isLoading = false) }
    fun signInFailed() {
        mutableState.value = AuthState(isLoading = false, error = "Could not sign in. Please try again.")
    }

    suspend fun signIn(idToken: String) = withContext(Dispatchers.IO) {
        try {
            val session = api.signIn(GoogleSignInRequest(idToken)).bodyOrThrow()
            require(session.token.isNotBlank() && session.user.id.isNotBlank() && session.expiresAt > nowSeconds())
            activate(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Temporary safe diagnostics: no tokens, response bodies, or exception messages.
            if (BuildConfig.DEBUG) {
                runCatching {
                    Log.e("SaayaAuth", "Sign-in completion failed: type=${failure.javaClass.simpleName}, " +
                        "httpStatus=${(failure as? HttpException)?.code() ?: "none"}")
                }
            }
            signInFailed()
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val session = sessions.current
        sessions.clear()
        mutableState.value = AuthState()
        try {
            if (session != null) withTimeoutOrNull(5000) { api.signOut("Bearer ${session.token}") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local sign-out still succeeds when offline; the server token expires independently.
        } finally {
            sessions.clear()
            clearProfile()
            mutableState.value = AuthState(isLoading = false)
        }
    }

    private suspend fun activate(session: AuthSession) {
        bindUser(session.user)
        sessions.activate(session)
        mutableState.value = AuthState(isLoading = false, user = session.user)
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: error("Missing authentication response")
    }
}
