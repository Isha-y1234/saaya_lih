package com.project.saaya.data.model

/** UTC timestamps use the backend's ISO-8601 representation. Ownership is assigned server-side. */
data class Incident(
    val id: String,
    val ownerId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val startedAt: String,
    val endedAt: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class IncidentCreate(val latitude: Double? = null, val longitude: Double? = null)
data class IncidentsResponse(val incidents: List<Incident>)
