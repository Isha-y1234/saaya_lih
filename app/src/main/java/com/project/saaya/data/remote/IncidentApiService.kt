package com.project.saaya.data.remote

import com.project.saaya.data.model.Incident
import com.project.saaya.data.model.IncidentCreate
import com.project.saaya.data.model.IncidentsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Uses the existing authenticated Retrofit instance; never sends a client-selected ownerId. */
interface IncidentApiService {
    @POST("incidents")
    suspend fun createIncident(@Body payload: IncidentCreate): Response<Incident>

    @GET("incidents/{id}")
    suspend fun getIncident(@Path("id") id: String): Response<Incident>

    @GET("incidents")
    suspend fun getIncidents(): Response<IncidentsResponse>

    @PATCH("incidents/{id}/end")
    suspend fun endIncident(@Path("id") id: String): Response<Incident>
}
