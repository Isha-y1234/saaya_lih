package com.project.saaya.data.repository

import com.project.saaya.data.model.AuthSession
import com.project.saaya.data.remote.DeviceTokenApiService
import com.project.saaya.data.remote.DeviceTokenRegistration
import com.project.saaya.data.remote.DeviceTokenRemoval
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.security.MessageDigest

interface TokenRegistrationCache {
    fun matches(fingerprint: String, nowMillis: Long): Boolean
    fun save(fingerprint: String, nowMillis: Long)
    fun clear()
}

class DeviceTokenRepository(private val api: DeviceTokenApiService, private val cache: TokenRegistrationCache,
                            private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()

    suspend fun register(token: String, session: AuthSession, notificationsEnabled: Boolean,
                         sessionIsCurrent: () -> Boolean): Boolean = mutex.withLock {
        require(token.isNotBlank())
        if (!sessionIsCurrent()) return@withLock false
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${session.user.id}\n${session.token}\n$token\n$notificationsEnabled".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val now = nowMillis()
        if (cache.matches(fingerprint, now)) return@withLock true
        val response = api.register("Bearer ${session.token}", DeviceTokenRegistration(token, notificationsEnabled))
        if (!response.isSuccessful) throw HttpException(response)
        if (!sessionIsCurrent()) return@withLock false
        cache.save(fingerprint, now)
        true
    }

    suspend fun unregister(token: String, session: AuthSession) {
        cache.clear()
        val response = api.unregister("Bearer ${session.token}", DeviceTokenRemoval(token))
        if (!response.isSuccessful) throw HttpException(response)
    }
}
