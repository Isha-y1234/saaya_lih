package com.project.saaya.data.repository

import com.project.saaya.data.model.Incident
import com.project.saaya.data.model.IncidentCreate
import com.project.saaya.data.model.IncidentsResponse
import com.project.saaya.data.remote.IncidentApiService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

internal fun incidentFixture() = Incident("incident-001", "stable-owner", "ACTIVE",
    "2026-10-02T10:00:00Z", "2026-10-02T10:00:00Z", "2026-10-02T10:00:00Z")

internal class IncidentFakeApi : IncidentApiService {
    var incident = incidentFixture()
    val calls = mutableListOf<String>()
    var createResponse: Response<Incident>? = null
    var endResponse: Response<Incident>? = null
    var loadResponse: Response<IncidentsResponse>? = null
    var payload: IncidentCreate? = null
    var createGate: CompletableDeferred<Unit>? = null
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun createIncident(payload: IncidentCreate): Response<Incident> {
        calls.add("POST")
        this.payload = payload
        createGate?.await()
        return createResponse ?: Response.success(incident)
    }
    override suspend fun getIncident(id: String): Response<Incident> {
        calls.add("GET:$id")
        return Response.success(incident)
    }
    override suspend fun getIncidents(): Response<IncidentsResponse> {
        calls.add("LIST")
        loadGate?.await()
        return loadResponse ?: Response.success(IncidentsResponse(listOf(incident)))
    }
    override suspend fun endIncident(id: String): Response<Incident> {
        calls.add("END:$id")
        return endResponse ?: Response.success(incident.copy(status = "ENDED", endedAt = "2026-10-02T11:00:00Z",
            updatedAt = "2026-10-02T11:00:00Z"))
    }
}

class IncidentRepositoryTest {
    private val api = IncidentFakeApi()
    private val repository = IncidentRepository(api, "stable-owner")

    @Test fun lifecycleUsesReturnedBackendIdentityAndCoordinates() = runBlocking {
        val created = repository.createIncident(12.9, 77.5)
        assertEquals(IncidentCreate(12.9, 77.5), api.payload)
        assertEquals(created, repository.getIncident(created.id))
        assertEquals(listOf(created), repository.loadIncidents())
        assertEquals("ENDED", repository.endIncident(created.id).status)
        assertEquals(listOf("POST", "GET:incident-001", "LIST", "END:incident-001"), api.calls)
    }

    @Test fun serverFailureIsNotTreatedAsSuccessfulCreation() {
        api.createResponse = Response.error(503, "Unavailable".toResponseBody())
        assertThrows(HttpException::class.java) { runBlocking { repository.createIncident() } }
    }

    @Test fun anotherOwnersResponseCannotActivateSos() {
        api.incident = api.incident.copy(ownerId = "other-owner")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createIncident() } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.loadIncidents() } }
    }

    @Test fun invalidCoordinatesAndEmptyIdsNeverReachApi() {
        listOf(Pair(91.0, 0.0), Pair(0.0, -181.0), Pair(Double.NaN, 0.0), Pair(1.0, null)).forEach { (lat, lon) ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createIncident(lat, lon) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.getIncident("") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.endIncident("") } }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun malformedEndedLifecycleIsRejected() {
        api.incident = api.incident.copy(status = "ENDED", endedAt = null)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createIncident() } }
    }
}
