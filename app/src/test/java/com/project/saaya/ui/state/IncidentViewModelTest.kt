package com.project.saaya.ui.state

import com.project.saaya.data.repository.IncidentFakeApi
import com.project.saaya.data.repository.IncidentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = IncidentFakeApi()
    private lateinit var viewModel: IncidentViewModel
    private val phone = "+919876543210"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = IncidentViewModel(IncidentRepository(api, "stable-owner"))
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun createsIncidentBeforeCallAndDoubleTapsProduceOneRequest() = runTest(dispatcher) {
        api.createGate = CompletableDeferred()
        viewModel.startSos(phone)
        viewModel.startSos(phone)
        runCurrent()
        assertEquals(listOf("POST"), api.calls)
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.activeIncident)
        assertNull(viewModel.consumeCall())
        api.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("incident-001", viewModel.uiState.value.activeIncident!!.id)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(phone, viewModel.consumeCall())
        assertNull(viewModel.consumeCall())
    }

    @Test fun failedCreationOffersExplicitFallbackAndRetry() = runTest(dispatcher) {
        api.createResponse = Response.error(503, "Unavailable".toResponseBody())
        viewModel.startSos(phone)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeIncident)
        assertNull(viewModel.uiState.value.pendingCallNumber)
        assertEquals(phone, viewModel.uiState.value.fallbackCallNumber)
        assertNotNull(viewModel.uiState.value.error)
        viewModel.callAnyway()
        assertEquals(phone, viewModel.consumeCall())
        assertNull(viewModel.consumeCall())
        api.createResponse = null
        viewModel.startSos(phone)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.activeIncident)
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun callingDuringSlowCreationNeverCallsAgainOnLateSuccess() = runTest(dispatcher) {
        api.createGate = CompletableDeferred()
        viewModel.startSos(phone)
        runCurrent()
        viewModel.callAnyway()
        assertEquals(phone, viewModel.consumeCall())
        api.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.activeIncident)
        assertNull(viewModel.consumeCall())
    }

    @Test fun restoresActiveIncidentAndEndsOnlyAfterServerSuccess() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("incident-001", viewModel.uiState.value.activeIncident!!.id)
        api.endResponse = Response.error(503, "Unavailable".toResponseBody())
        viewModel.endIncident("incident-001")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.activeIncident)
        assertNotNull(viewModel.uiState.value.error)
        api.endResponse = null
        viewModel.endIncident("incident-001")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeIncident)
        assertEquals("ENDED", viewModel.uiState.value.incidents.single().status)
    }

    @Test fun failedRefreshKeepsKnownActiveIncident() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        api.loadResponse = Response.error(503, "Unavailable".toResponseBody())
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("incident-001", viewModel.uiState.value.activeIncident!!.id)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test fun sosPreemptsSlowBackgroundRestore() = runTest(dispatcher) {
        api.loadGate = CompletableDeferred()
        viewModel.refresh()
        runCurrent()
        viewModel.startSos(phone)
        advanceUntilIdle()
        assertEquals(listOf("LIST", "POST"), api.calls)
        assertEquals(phone, viewModel.consumeCall())
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun leavingAuthenticatedHostCancelsPendingCallAndClearsState() = runTest(dispatcher) {
        api.createGate = CompletableDeferred()
        viewModel.startSos(phone)
        runCurrent()
        viewModel.clearSessionState()
        api.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(IncidentUiState(), viewModel.uiState.value)
        assertNull(viewModel.consumeCall())
    }

    @Test fun loadsOneIncidentById() = runTest(dispatcher) {
        viewModel.loadIncident("incident-001")
        advanceUntilIdle()
        assertEquals(listOf("GET:incident-001"), api.calls)
        assertEquals("incident-001", viewModel.uiState.value.activeIncident!!.id)
    }
}
