package com.project.saaya.data.repository

import com.project.saaya.data.model.Incident
import com.project.saaya.data.model.IncidentCreate
import com.project.saaya.data.remote.IncidentApiService
import retrofit2.HttpException
import retrofit2.Response

class IncidentRepository(private val api: IncidentApiService, private val authenticatedUserId: String) {
    init { require(authenticatedUserId.isNotBlank()) }

    suspend fun createIncident(latitude: Double? = null, longitude: Double? = null): Incident {
        require((latitude == null) == (longitude == null))
        require(latitude == null || latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude == null || longitude.isFinite() && longitude in -180.0..180.0)
        return validate(api.createIncident(IncidentCreate(latitude, longitude)).bodyOrThrow())
    }

    suspend fun getIncident(id: String): Incident {
        require(id.isNotBlank())
        return validate(api.getIncident(id).bodyOrThrow())
    }

    suspend fun loadIncidents(): List<Incident> = api.getIncidents().bodyOrThrow().incidents.map(::validate)

    suspend fun endIncident(id: String): Incident {
        require(id.isNotBlank())
        return validate(api.endIncident(id).bodyOrThrow()).also { require(it.status == "ENDED") }
    }

    private fun validate(incident: Incident): Incident = incident.also {
        require(it.id.isNotBlank() && it.ownerId == authenticatedUserId) { "Invalid incident identity" }
        require(it.status == "ACTIVE" || it.status == "ENDED") { "Invalid incident status" }
        require(it.createdAt.isNotBlank() && it.updatedAt.isNotBlank() && it.startedAt.isNotBlank())
        require((it.status == "ACTIVE") == (it.endedAt == null)) { "Invalid incident lifecycle" }
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: error("Missing incident response")
    }
}
