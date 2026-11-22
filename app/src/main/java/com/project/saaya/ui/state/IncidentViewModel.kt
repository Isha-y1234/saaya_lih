package com.project.saaya.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.project.saaya.data.model.Incident
import com.project.saaya.data.repository.IncidentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IncidentUiState(
    val isLoading: Boolean = false,
    val isStartingSos: Boolean = false,
    val isRefreshing: Boolean = false,
    val incidents: List<Incident> = emptyList(),
    val activeIncident: Incident? = null,
    val error: String? = null,
    val pendingCallNumber: String? = null,
    val fallbackCallNumber: String? = null
)

class IncidentViewModel(private val repository: IncidentRepository) : ViewModel() {
    private val state = MutableStateFlow(IncidentUiState())
    val uiState = state.asStateFlow()
    private var operation: Job? = null
    private var operationVersion = 0
    private var sosCallRequested = false

    fun refresh() = perform("Could not load incidents. Check your connection and retry.", refreshing = true) {
        val incidents = repository.loadIncidents()
        state.value = state.value.copy(incidents = incidents, activeIncident = incidents.firstOrNull { it.status == "ACTIVE" })
    }

    fun loadIncident(id: String) = perform("Could not load this incident.") { rememberIncident(repository.getIncident(id)) }

    fun startSos(phoneNumber: String, latitude: Double? = null, longitude: Double? = null) {
        // A background restore must never delay an emergency request.
        if (state.value.isRefreshing) {
            operationVersion++
            operation?.cancel()
            state.value = state.value.copy(isLoading = false, isRefreshing = false)
        }
        if (state.value.isLoading || state.value.pendingCallNumber != null) return
        require(phoneNumber.isNotBlank())
        sosCallRequested = true
        perform("Could not confirm an SOS incident. Check your connection and retry, or call your contact anyway.", true) {
            // POST resumes the server's existing ACTIVE incident, making retries/reopening safe.
            val incident = repository.createIncident(latitude, longitude)
            require(incident.status == "ACTIVE")
            rememberIncident(incident)
            if (sosCallRequested) {
                sosCallRequested = false
                state.value = state.value.copy(pendingCallNumber = phoneNumber)
            }
        }
        // Retained only to offer an explicit emergency-call fallback if the request fails.
        state.value = state.value.copy(fallbackCallNumber = phoneNumber)
    }

    fun endIncident(id: String) = perform("Could not end SOS. The incident remains active; please retry.") {
        rememberIncident(repository.endIncident(id))
    }

    fun consumeCall(): String? {
        val number = state.value.pendingCallNumber ?: return null
        state.value = state.value.copy(pendingCallNumber = null, fallbackCallNumber = null)
        return number
    }

    fun callAnyway() {
        val number = state.value.fallbackCallNumber ?: return
        sosCallRequested = false
        state.value = state.value.copy(pendingCallNumber = number, error = null, fallbackCallNumber = null)
    }

    fun dismissError() { state.value = state.value.copy(error = null, fallbackCallNumber = null) }

    /** Drop an old account's state and pending call when the authenticated host leaves composition. */
    fun clearSessionState() {
        operationVersion++
        sosCallRequested = false
        operation?.cancel()
        state.value = IncidentUiState()
    }

    private fun rememberIncident(incident: Incident) {
        val incidents = listOf(incident) + state.value.incidents.filterNot { it.id == incident.id }
        state.value = state.value.copy(incidents = incidents, activeIncident = incidents.firstOrNull { it.status == "ACTIVE" })
    }

    private fun perform(error: String, startingSos: Boolean = false, refreshing: Boolean = false, block: suspend () -> Unit) {
        if (state.value.isLoading) return
        val version = ++operationVersion
        state.value = state.value.copy(isLoading = true, isStartingSos = startingSos,
            isRefreshing = refreshing, error = null, fallbackCallNumber = null)
        operation = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == operationVersion) state.value = state.value.copy(error = error)
            } finally {
                if (version == operationVersion) {
                    state.value = state.value.copy(isLoading = false, isStartingSos = false, isRefreshing = false)
                }
            }
        }
    }

    class Factory(private val repository: IncidentRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == IncidentViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return IncidentViewModel(repository) as T
        }
    }
}
