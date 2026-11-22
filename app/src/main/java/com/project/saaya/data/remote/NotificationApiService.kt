package com.project.saaya.data.remote

import com.project.saaya.data.model.Incident
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface NotificationApiService {
    @GET("incident-alerts/{id}") suspend fun getAlert(@Path("id") incidentId: String): Response<Incident>
    @POST("incident-alerts/{id}/dismiss") suspend fun dismissAlert(@Path("id") incidentId: String): Response<Unit>
}
