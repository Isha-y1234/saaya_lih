package com.project.saaya.data.repository

import com.project.saaya.data.auth.SessionManager
import com.project.saaya.data.local.AuthSessionStore
import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.remote.GoogleSignInRequest
import com.project.saaya.data.remote.UserApiService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AuthRepositoryTest {
    private val user = AuthenticatedUser("stable-mongo-user", "Ananya", "ananya@example.com", "SAAYA-507F1F77BCF86CD799439011")
    private val store = MemoryStore()
    private val api = FakeApi(user)
    private val boundUsers = mutableListOf<String>()
    private var profileCleared = false
    private val sessions = SessionManager(store)
    private fun repository(manager: SessionManager = sessions) = AuthRepository(
        api, manager, { boundUsers.add(it.id) }, { profileCleared = true }, nowSeconds = { 100 }
    )

    @Test fun signInPersistsServerIdentityAndSession() = runBlocking {
        val repository = repository()
        repository.beginSignIn()
        repository.signIn("google-id-token-fixture")
        assertEquals("google-id-token-fixture", api.googleToken)
        assertEquals(user.id, store.value!!.user.id)
        assertEquals("opaque-session-fixture", sessions.current!!.token)
        assertEquals(listOf(user.id), boundUsers)
        assertEquals(user, repository.state.value.user)
        assertEquals(user.saayaId, repository.state.value.user!!.saayaId)
        assertFalse(repository.state.value.isLoading)
    }

    @Test fun reopenedAppValidatesPersistedSessionBeforeGrantingAccess() = runBlocking {
        store.value = AuthSession("persisted-session-fixture", user.copy(saayaId = null), 200)
        val freshManager = SessionManager(store)
        val freshRepository = repository(freshManager)
        assertNull(freshRepository.state.value.user)
        freshRepository.restore()
        assertEquals("Bearer persisted-session-fixture", api.authorization)
        assertEquals(user.id, freshRepository.state.value.user!!.id)
        assertEquals(user.saayaId, freshRepository.state.value.user!!.saayaId)
        assertEquals("persisted-session-fixture", freshManager.current!!.token)
        assertNull(api.googleToken)
    }

    @Test fun expiredSessionNeverFallsBackToTemporaryOwner() = runBlocking {
        store.value = AuthSession("expired-session-fixture", user, 99)
        val repository = repository()
        repository.restore()
        assertNull(repository.state.value.user)
        assertNull(sessions.current)
        assertNull(store.value)
        assertNull(api.authorization)
    }

    @Test fun rejectedSessionIsCleared() = runBlocking {
        store.value = AuthSession("revoked-session-fixture", user, 200)
        api.meResponse = Response.error(401, "Unauthorized".toResponseBody())
        val repository = repository()
        repository.restore()
        assertNull(store.value)
        assertNull(repository.state.value.user)
        assertFalse(repository.state.value.canRetryRestore)
    }

    @Test fun networkOutagePreservesSavedSessionButDoesNotGrantAccess() = runBlocking {
        store.value = AuthSession("offline-session-fixture", user, 200)
        api.offline = true
        val repository = repository()
        repository.restore()
        assertNotNull(store.value)
        assertNull(sessions.current)
        assertNull(repository.state.value.user)
        assertTrue(repository.state.value.canRetryRestore)
        api.offline = false
        repository.restore()
        assertEquals(user.id, repository.state.value.user!!.id)
    }

    @Test fun sessionIdentityMismatchIsRejected() = runBlocking {
        store.value = AuthSession("wrong-session-fixture", user.copy(id = "other-user"), 200)
        val repository = repository()
        repository.restore()
        assertNull(store.value)
        assertNull(repository.state.value.user)
    }

    @Test fun offlineSignOutClearsSessionAndAccountPreferences() = runBlocking {
        val repository = repository()
        repository.signIn("google-token-fixture")
        api.offline = true
        repository.signOut()
        assertTrue(profileCleared)
        assertNull(store.value)
        assertNull(sessions.current)
        assertNull(repository.state.value.user)
    }

    @Test fun failedGoogleExchangeNeverPersistsSession() = runBlocking {
        api.loginResponse = Response.error(401, "Bad Google token".toResponseBody())
        val repository = repository()
        repository.signIn("invalid-google-token-fixture")
        assertNull(store.value)
        assertNull(repository.state.value.user)
        assertFalse(repository.state.value.isLoading)
        assertNotNull(repository.state.value.error)
    }

    @Test fun switchingAccountBindsTheNewServerOwner() = runBlocking {
        val repository = repository()
        repository.signIn("google-token-a-fixture")
        val other = user.copy(id = "another-stable-user", email = "other@example.com")
        api.loginResponse = Response.success(AuthSession("session-b-fixture", other, 200))
        repository.signIn("google-token-b-fixture")
        assertEquals(listOf(user.id, other.id), boundUsers)
        assertEquals(other.id, sessions.current!!.user.id)
        assertEquals(other.id, store.value!!.user.id)
    }

    @Test fun profileRefreshUsesAuthenticatedMeAndDoesNotChangeEmergencyPreferences() = runBlocking {
        val repository = repository()
        repository.signIn("google-token")
        val gate = CompletableDeferred<Unit>()
        api.meGate = gate
        val request = async { repository.refreshProfile() }
        yield()
        assertTrue(repository.state.value.isRefreshingProfile)
        assertFalse(repository.state.value.isLoading)
        gate.complete(Unit)
        request.await()
        assertEquals("Bearer opaque-session-fixture", api.authorization)
        assertEquals(user.saayaId, repository.state.value.user!!.saayaId)
        assertEquals(listOf(user.id), boundUsers) // No profile-preference rebinding on refresh.
        assertFalse(profileCleared)
        assertFalse(repository.state.value.isRefreshingProfile)
        assertNull(repository.state.value.profileError)
    }

    @Test fun missingIdAndApiFailureAreRetryableWithoutLosingAuthentication() = runBlocking {
        val repository = repository()
        repository.signIn("google-token")
        api.meResponse = Response.success(user.copy(saayaId = null))
        repository.refreshProfile()
        assertNull(repository.state.value.user!!.saayaId)
        assertNotNull(repository.state.value.profileError)
        api.meResponse = Response.error(503, "Unavailable".toResponseBody())
        repository.refreshProfile()
        assertNotNull(repository.state.value.profileError)
        assertNotNull(repository.state.value.user)
        assertNotNull(sessions.current)
        assertFalse(repository.state.value.isRefreshingProfile)
        api.meResponse = Response.success(user)
        repository.refreshProfile()
        assertEquals(user.saayaId, repository.state.value.user!!.saayaId)
        assertNull(repository.state.value.profileError)
    }

    @Test fun profileRefreshRejectsAnotherAccountsId() = runBlocking {
        val repository = repository()
        repository.signIn("google-token")
        api.meResponse = Response.success(user.copy(id = "other", saayaId = "OTHER-ID"))
        repository.refreshProfile()
        assertEquals(user.saayaId, repository.state.value.user!!.saayaId)
        assertNotNull(repository.state.value.profileError)
    }

    @Test fun lateProfileResponseCannotOverwriteNewAccount() = runBlocking {
        val repository = repository()
        repository.signIn("google-token-a")
        val gate = CompletableDeferred<Unit>()
        api.meGate = gate
        val pending = async { repository.refreshProfile() }
        yield()
        val other = user.copy(id = "other", saayaId = "SAAYA-507F1F77BCF86CD799439012")
        api.loginResponse = Response.success(AuthSession("session-b", other, 200))
        repository.signIn("google-token-b")
        gate.complete(Unit)
        pending.await()
        assertEquals(other, repository.state.value.user)
        assertEquals(other.saayaId, sessions.current!!.user.saayaId)
        assertNull(repository.state.value.profileError)
    }

    private class MemoryStore : AuthSessionStore {
        var value: AuthSession? = null
        override fun read() = value
        override fun write(session: AuthSession) { value = session }
        override fun clear() { value = null }
    }

    private class FakeApi(user: AuthenticatedUser) : UserApiService {
        var loginResponse: Response<AuthSession> = Response.success(AuthSession("opaque-session-fixture", user, 200))
        var meResponse: Response<AuthenticatedUser> = Response.success(user)
        var googleToken: String? = null
        var authorization: String? = null
        var offline = false
        var meGate: CompletableDeferred<Unit>? = null
        override suspend fun signIn(request: GoogleSignInRequest): Response<AuthSession> {
            googleToken = request.idToken
            return loginResponse
        }
        override suspend fun getMe(authorization: String): Response<AuthenticatedUser> {
            this.authorization = authorization
            meGate?.await()
            if (offline) throw IOException("Offline")
            return meResponse
        }
        override suspend fun signOut(authorization: String): Response<Unit> {
            if (offline) throw IOException("Offline")
            return Response.success<Unit>(204, null)
        }
    }
}
