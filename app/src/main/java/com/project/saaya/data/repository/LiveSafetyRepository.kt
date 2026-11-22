package com.project.saaya.data.repository

import com.project.saaya.data.remote.LiveSafetyApiService
import com.project.saaya.data.remote.LiveSafetyJoin
import retrofit2.HttpException
import retrofit2.Response

class LiveSafetyRepository(private val api: LiveSafetyApiService) {
    suspend fun enableViewerAudio(id: String) {
        val response = api.enableViewerAudio(id)
        if (!response.isSuccessful) throw HttpException(response)
    }
    suspend fun activeIncidents() = api.activeIncidents().bodyOrThrow().incidents
    suspend fun status(id: String, verifySession: Boolean = false) = api.status(id, verifySession).bodyOrThrow().also {
        require(it.incidentId == id && it.role in setOf("publisher", "viewer"))
    }
    suspend fun rejoin(id: String): LiveSafetyJoin = validateJoin(id, api.rejoin(id).bodyOrThrow())
    suspend fun join(id: String): LiveSafetyJoin = validateJoin(id, api.join(id).bodyOrThrow())
    private fun validateJoin(id: String, credential: LiveSafetyJoin): LiveSafetyJoin = credential.also {
        require(it.incidentId == id && it.role in setOf("publisher", "viewer"))
        require(it.url.startsWith("wss://") && it.token.isNotBlank() && it.expiresIn in 1..600)
    }
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: error("Live safety response unavailable")
    }
}
