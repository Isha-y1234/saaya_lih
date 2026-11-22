package com.project.saaya.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import com.project.saaya.data.model.IncidentsResponse

data class LiveSafetyViewer(val identity: String, val name: String)
data class LiveSafetyStatus(val incidentId: String, val active: Boolean, val readiness: String, val role: String,
    val sessionActive: Boolean = false, val viewers: List<LiveSafetyViewer>? = null)
// Avoid a data class: generated toString() must never include a media credential.
class LiveSafetyJoin(val incidentId: String, val url: String, val token: String, val role: String, val expiresIn: Int)

interface LiveSafetyApiService {
    @GET("live-safety/incidents") suspend fun activeIncidents(): Response<IncidentsResponse>
    @GET("incidents/{id}/live") suspend fun status(@Path("id") incidentId: String, @Query("verifySession") verifySession: Boolean = false): Response<LiveSafetyStatus>
    @POST("incidents/{id}/live/audio") suspend fun enableViewerAudio(@Path("id") incidentId: String): Response<Unit>
    @POST("incidents/{id}/live/rejoin") suspend fun rejoin(@Path("id") incidentId: String): Response<LiveSafetyJoin>
    @POST("incidents/{id}/live/join") suspend fun join(@Path("id") incidentId: String): Response<LiveSafetyJoin>
}
