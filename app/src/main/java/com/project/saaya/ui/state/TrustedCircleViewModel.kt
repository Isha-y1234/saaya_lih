package com.project.saaya.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.ContactInvitationResponse
import retrofit2.HttpException
import com.project.saaya.data.repository.TrustedCircleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class TrustedCircleUiState(
    val isLoading: Boolean = false,
    val contacts: List<TrustedContact> = emptyList(),
    val error: String? = null,
    val incomingInvitations: List<ContactInvitationResponse> = emptyList(),
    val successMessage: String? = null
)

/** Screen loads explicitly; the injected repository supplies the same owner for every request. */
class TrustedCircleViewModel(
    private val repository: TrustedCircleRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TrustedCircleUiState())
    val uiState: StateFlow<TrustedCircleUiState> = mutableUiState.asStateFlow()

    // A saved contact survives a failed invitation send so Retry does not create it twice.
    private var savedDraft: TrustedContact? = null
    private var activeOperation: Job? = null
    private var operationVersion = 0
    private var refreshRequested = false

    fun clearSessionState() {
        operationVersion++
        activeOperation?.cancel()
        savedDraft = null
        refreshRequested = false
        mutableUiState.value = TrustedCircleUiState()
    }

    fun loadContacts() = refresh()

    fun refresh() {
        if (mutableUiState.value.isLoading) {
            refreshRequested = true
            return
        }
        performOperation("Could not refresh your circle. Check your connection and retry.") { refreshLists() }
    }

    fun addContact(contact: TrustedContact, onSaved: () -> Unit = {}) = performOperation("Could not add contact") {
        if (savedDraft?.copy(id = "") != contact) repository.addContact(contact)
        savedDraft = null
        succeeded("Contact saved.", onSaved)
        reloadAfterWrite()
    }

    fun deleteContact(id: String, onDeleted: () -> Unit = {}) = performOperation("Could not remove contact") {
        repository.deleteContact(id)
        if (savedDraft?.id == id) savedDraft = null
        mutableUiState.value = mutableUiState.value.copy(contacts = mutableUiState.value.contacts.filterNot { it.id == id })
        succeeded("Contact removed.", onDeleted)
        reloadAfterWrite()
    }

    fun sendInvitation(saayaId: String, contact: TrustedContact, onSent: () -> Unit = {}) =
        performOperation("Could not send invitation. Your saved contact is available to retry.") {
            val saved = when {
                contact.id.isNotBlank() -> contact
                savedDraft?.copy(id = "") == contact -> savedDraft!!
                else -> repository.createInvitationContact(contact).also {
                    savedDraft = it
                    mutableUiState.value = mutableUiState.value.copy(contacts = mutableUiState.value.contacts + it)
                }
            }
            val result = repository.sendInvitation(saved.id, saayaId)
            mutableUiState.value = mutableUiState.value.copy(contacts = mutableUiState.value.contacts.map {
                if (it.id == saved.id) it.copy(invitationStatus = result.invitationStatus) else it
            })
            savedDraft = null
            succeeded("Invitation sent. Waiting for acceptance.", onSent)
            reloadAfterWrite()
        }

    fun acceptInvitation(invitation: ContactInvitationResponse) = respond(invitation, true)
    fun rejectInvitation(invitation: ContactInvitationResponse) = respond(invitation, false)

    private fun respond(invitation: ContactInvitationResponse, accept: Boolean) =
        performOperation("Could not respond to invitation. Refresh and retry.") {
            val token = requireNotNull(invitation.invitationId) { "Refresh this invitation before responding." }
            if (accept) repository.acceptInvitation(invitation.id, token)
            else repository.rejectInvitation(invitation.id, token)
            mutableUiState.value = mutableUiState.value.copy(incomingInvitations =
                mutableUiState.value.incomingInvitations.filterNot { it.invitationId == token })
            succeeded(if (accept) "Invitation accepted." else "Invitation rejected.")
            reloadAfterWrite()
        }

    private fun succeeded(message: String, callback: () -> Unit = {}) {
        mutableUiState.value = mutableUiState.value.copy(successMessage = message)
        callback()
    }

    private suspend fun refreshLists() {
        var failure: Exception? = null
        try {
            val contacts = repository.loadContacts()
            if (savedDraft != null && contacts.none { it.id == savedDraft?.id }) savedDraft = null
            mutableUiState.value = mutableUiState.value.copy(contacts = contacts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        // Incoming invitations remain available even if the owned-contact list fails.
        try {
            val incoming = repository.loadIncomingInvitations()
            mutableUiState.value = mutableUiState.value.copy(incomingInvitations = incoming)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (failure == null) failure = error
        }
        failure?.let { throw it }
    }

    private suspend fun reloadAfterWrite() = try {
        refreshLists()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IllegalStateException("Change completed, but refresh failed. Refresh your circle to sync.", failure)
    }

    private fun performOperation(fallbackError: String, operation: suspend () -> Unit) {
        if (mutableUiState.value.isLoading) return
        mutableUiState.value = mutableUiState.value.copy(isLoading = true, error = null, successMessage = null)
        val version = ++operationVersion
        activeOperation = viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = when (failure) {
                    is HttpException -> when (failure.code()) {
                        401 -> "Your session has expired. Sign in again to continue."
                        403 -> "This action is not allowed for your account."
                        404 -> "The Saaya account, contact or invitation was not found. Check the ID and refresh."
                        409 -> "An invitation already exists or has changed. Refresh your circle before retrying."
                        422 -> "Check the Saaya ID and emergency mobile number."
                        else -> fallbackError
                    }
                    is IllegalStateException -> if (failure.message?.startsWith("Change completed") == true)
                        failure.message!! else fallbackError
                    else -> fallbackError
                }
                mutableUiState.value = mutableUiState.value.copy(error = message)
            } finally {
                if (version == operationVersion) {
                    mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                    if (refreshRequested) {
                        refreshRequested = false
                        refresh()
                    }
                }
            }
        }
    }

    /** Manual DI entry point for future screen integration. */
    class Factory(private val repository: TrustedCircleRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TrustedCircleViewModel::class.java) { "Unsupported ViewModel" }
            @Suppress("UNCHECKED_CAST")
            return TrustedCircleViewModel(repository) as T
        }
    }
}
