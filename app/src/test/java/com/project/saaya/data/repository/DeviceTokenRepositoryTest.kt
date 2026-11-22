package com.project.saaya.data.repository

import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.model.AuthenticatedUser
import com.project.saaya.data.remote.DeviceTokenApiService
import com.project.saaya.data.remote.DeviceTokenRegistration
import com.project.saaya.data.remote.DeviceTokenRemoval
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DeviceTokenRepositoryTest {
    private val session = AuthSession("session-fixture", AuthenticatedUser("owner", "Test", "test@example.com"), 2000000000)
    private val api = FakeApi()
    private val cache = Cache()
    private var now = 1000000000L
    private val repository = DeviceTokenRepository(api, cache) { now }

    @Test fun deduplicatesButUpdatesForTokenPermissionOrSessionChanges() = runBlocking {
        repository.register("fcm-token", session, true) { true }
        repository.register("fcm-token", session, true) { true }
        assertEquals(1, api.registrations.size)
        repository.register("new-fcm-token", session, true) { true }
        repository.register("new-fcm-token", session, false) { true }
        repository.register("new-fcm-token", AuthSession("new-session",
            session.user.copy(id = "new-owner"), session.expiresAt), false) { true }
        assertEquals(4, api.registrations.size)
        assertEquals("Bearer new-session", api.authorization)
        assertFalse(cache.fingerprint!!.contains("new-fcm-token"))
        assertFalse(cache.fingerprint!!.contains("new-session"))
    }

    @Test fun refreshesRegistrationAfterOneDay() = runBlocking {
        repository.register("fcm-token", session, true) { true }
        now += 24 * 60 * 60 * 1000L
        repository.register("fcm-token", session, true) { true }
        assertEquals(2, api.registrations.size)
    }

    @Test fun failedRegistrationIsNotCachedAndCanRetry() {
        api.response = Response.error(503, "Unavailable".toResponseBody())
        assertThrows(HttpException::class.java) { runBlocking { repository.register("fcm-token", session, true) { true } } }
        assertNull(cache.fingerprint)
        api.response = Response.success(Unit)
        runBlocking { repository.register("fcm-token", session, true) { true } }
        assertEquals(2, api.registrations.size)
    }

    @Test fun logoutOrAccountChangeNeverCachesLateRegistration() = runBlocking {
        assertFalse(repository.register("fcm-token", session, true) { false })
        assertTrue(api.registrations.isEmpty())
        var checks = 0
        assertFalse(repository.register("fcm-token", session, true) { checks++ == 0 })
        assertNull(cache.fingerprint)
    }

    @Test fun removalUsesSessionAndTokenAndClearsCache() = runBlocking {
        repository.register("fcm-token", session, true) { true }
        repository.unregister("fcm-token", session)
        assertEquals("Bearer session-fixture", api.authorization)
        assertEquals(DeviceTokenRemoval("fcm-token"), api.removal)
        assertNull(cache.fingerprint)
    }

    private class Cache : TokenRegistrationCache {
        var fingerprint: String? = null
        var savedAt = 0L
        override fun matches(fingerprint: String, nowMillis: Long) = this.fingerprint == fingerprint &&
            nowMillis - savedAt in 0 until 24 * 60 * 60 * 1000L
        override fun save(fingerprint: String, nowMillis: Long) { this.fingerprint = fingerprint; savedAt = nowMillis }
        override fun clear() { fingerprint = null }
    }
    private class FakeApi : DeviceTokenApiService {
        var authorization = ""
        val registrations = mutableListOf<DeviceTokenRegistration>()
        var removal: DeviceTokenRemoval? = null
        var response: Response<Unit> = Response.success(Unit)
        override suspend fun register(authorization: String, body: DeviceTokenRegistration): Response<Unit> {
            this.authorization = authorization
            registrations.add(body)
            return response
        }
        override suspend fun unregister(authorization: String, body: DeviceTokenRemoval): Response<Unit> {
            this.authorization = authorization; removal = body; return Response.success<Unit>(204, null)
        }
    }
}
