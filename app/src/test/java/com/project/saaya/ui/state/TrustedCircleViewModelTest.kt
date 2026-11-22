package com.project.saaya.ui.state

import com.google.gson.JsonElement
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.InvitationStatus
import com.project.saaya.data.remote.SaayaApiService
import com.project.saaya.data.remote.ContactInvitationRequest
import com.project.saaya.data.remote.ContactInvitationDecisionRequest
import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.IncomingInvitationsResponse
import com.project.saaya.data.repository.FastApiTrustedContactJsonMapper
import com.project.saaya.data.repository.TrustedCircleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedCircleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeApi()
    private lateinit var viewModel: TrustedCircleViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = TrustedCircleViewModel(
            TrustedCircleRepository(api, FastApiTrustedContactJsonMapper, "test-user-001")
        )
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun loadsAndRefreshesAfterAddAndDeleteWithSameOwner() = runTest(dispatcher) {
        viewModel.loadContacts()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
        val contact = viewModel.uiState.value.contacts.single()
        var saved = false
        viewModel.addContact(contact) { saved = true }
        advanceUntilIdle()
        assertTrue(saved)
        viewModel.deleteContact(contact.id)
        advanceUntilIdle()
        assertEquals(listOf("GET:test-user-001", "POST:test-user-001", "GET:test-user-001", "DELETE:backend-id", "GET:test-user-001"), api.calls)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun failedWritePreservesContactsAndDoesNotCloseDialogOrRefresh() = runTest(dispatcher) {
        viewModel.loadContacts()
        advanceUntilIdle()
        val previous = viewModel.uiState.value.contacts
        api.failWrite = true
        var saved = false
        viewModel.addContact(previous.single()) { saved = true }
        advanceUntilIdle()
        assertFalse(saved)
        assertEquals(previous, viewModel.uiState.value.contacts)
        assertEquals(2, api.calls.size)
        assertTrue(viewModel.uiState.value.error != null)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun completedWriteWithFailedRefreshReportsSyncErrorAndCanRefreshAgain() = runTest(dispatcher) {
        api.failLoad = true
        var saved = false
        viewModel.addContact(TrustedContact("Mum", "Family", "123", "M")) { saved = true }
        advanceUntilIdle()
        assertTrue(saved)
        assertTrue(viewModel.uiState.value.error!!.contains("Change completed"))
        assertFalse(viewModel.uiState.value.isLoading)
        api.failLoad = false
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.contacts.size)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun sendsInvitationThenRefreshesBothListsAndSerializesDoubleTap() = runTest(dispatcher) {
        val draft = TrustedContact("Friend", "Family", "+919876543210", "F")
        var completed = 0
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), draft) { completed++ }
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), draft) { completed++ }
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
        assertEquals(1, completed)
        assertEquals(1, api.creates)
        assertEquals(1, api.sends)
        assertEquals(1, api.incomingLoads)
        val sent = viewModel.uiState.value.contacts.single { it.id == "new-1" }
        assertEquals(InvitationStatus.PENDING, sent.invitationStatus)
        assertEquals(draft.phone, sent.phone)
        assertTrue(viewModel.uiState.value.successMessage!!.contains("Invitation sent"))
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun failedSendRetainsSavedContactAndRetryDoesNotCreateAnother() = runTest(dispatcher) {
        val draft = TrustedContact("Friend", "Family", "+919876543210", "F")
        api.failSend = true
        var completed = false
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), draft) { completed = true }
        advanceUntilIdle()
        assertFalse(completed)
        assertEquals(1, api.creates)
        assertEquals(draft.phone, viewModel.uiState.value.contacts.single().phone)
        assertTrue(viewModel.uiState.value.error!!.contains("invitation"))
        api.failSend = false
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), draft) { completed = true }
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(1, api.creates)
        assertEquals(2, api.sends)
    }

    @Test fun existingContactCanBeInvitedWithoutCreatingOrChangingPhone() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        val saved = viewModel.uiState.value.contacts.single()
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), saved)
        advanceUntilIdle()
        assertEquals(0, api.creates)
        assertEquals(saved.phone, viewModel.uiState.value.contacts.single().phone)
        assertEquals(InvitationStatus.PENDING, viewModel.uiState.value.contacts.single().invitationStatus)
    }

    @Test fun savedDraftCanBeKeptAsPhoneOnlyWithoutDuplicateCreation() = runTest(dispatcher) {
        val draft = TrustedContact("Friend", "Family", "123456", "F")
        api.failSend = true
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), draft)
        advanceUntilIdle()
        var saved = false
        viewModel.addContact(draft) { saved = true }
        advanceUntilIdle()
        assertTrue(saved)
        assertEquals(1, api.creates)
        assertTrue(api.calls.none { it.startsWith("POST:") })
        assertEquals("Contact saved.", viewModel.uiState.value.successMessage)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun acceptAndRejectRefreshIncomingAndPreserveOwnedContactNumbers() = runTest(dispatcher) {
        api.incoming = listOf(api.invitation("incoming-a"), api.invitation("incoming-b"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.incomingInvitations.size)
        viewModel.acceptInvitation(viewModel.uiState.value.incomingInvitations.first())
        advanceUntilIdle()
        assertEquals("Invitation accepted.", viewModel.uiState.value.successMessage)
        assertEquals(listOf("incoming-b"), viewModel.uiState.value.incomingInvitations.map { it.id })
        viewModel.rejectInvitation(viewModel.uiState.value.incomingInvitations.single())
        advanceUntilIdle()
        assertEquals("Invitation rejected.", viewModel.uiState.value.successMessage)
        assertTrue(viewModel.uiState.value.incomingInvitations.isEmpty())
        assertEquals(3, api.incomingLoads)
        assertEquals("123", viewModel.uiState.value.contacts.single().phone)
    }

    @Test fun failedDecisionPreservesIncomingAndShowsActionableConflict() = runTest(dispatcher) {
        api.incoming = listOf(api.invitation("incoming"))
        viewModel.refresh()
        advanceUntilIdle()
        api.failDecision = true
        viewModel.acceptInvitation(viewModel.uiState.value.incomingInvitations.single())
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.incomingInvitations.size)
        assertTrue(viewModel.uiState.value.error!!.contains("Refresh"))
        assertEquals(null, viewModel.uiState.value.successMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun successfulSendWithRefreshFailureDoesNotInviteAgainAndReportsCompletedChange() = runTest(dispatcher) {
        api.failLoad = true
        var completed = false
        viewModel.sendInvitation("SAAYA-" + "A".repeat(24), TrustedContact("Friend", "Family", "123", "F")) { completed = true }
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(1, api.sends)
        assertEquals(InvitationStatus.PENDING, viewModel.uiState.value.contacts.single().invitationStatus)
        assertTrue(viewModel.uiState.value.error!!.contains("Change completed"))
        assertTrue(viewModel.uiState.value.successMessage!!.contains("Invitation sent"))
        api.failLoad = false
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, api.sends)
    }

    @Test fun incomingFailureKeepsSuccessfullyLoadedContacts() = runTest(dispatcher) {
        api.failIncoming = true
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.contacts.size)
        assertTrue(viewModel.uiState.value.error != null)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun resumeOrNotificationRefreshWhileLoadingIsQueuedOnce() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        api.loadGate = gate
        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        viewModel.refresh()
        api.incoming = listOf(api.invitation("missed-push"))
        api.loadGate = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, api.incomingLoads)
        assertEquals("missed-push", viewModel.uiState.value.incomingInvitations.single().id)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun leavingAccountCancelsOldRefreshAndDropsPendingData() = runTest(dispatcher) {
        api.loadGate = CompletableDeferred()
        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        viewModel.clearSessionState()
        assertEquals(TrustedCircleUiState(), viewModel.uiState.value)
        api.loadGate = null
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, api.incomingLoads)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.contacts.size)
    }

    @Test fun contactsFailureDoesNotPreventMissedIncomingInvitationsLoading() = runTest(dispatcher) {
        api.failLoad = true
        api.incoming = listOf(api.invitation("missed-push"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("missed-push", viewModel.uiState.value.incomingInvitations.single().id)
        assertTrue(viewModel.uiState.value.error != null)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    private class FakeApi : SaayaApiService {
        val calls = mutableListOf<String>()
        var failWrite = false
        var failLoad = false
        var failSend = false
        var failDecision = false
        var failIncoming = false
        var loadGate: CompletableDeferred<Unit>? = null
        var creates = 0
        var sends = 0
        var incomingLoads = 0
        var incoming = emptyList<ContactInvitationResponse>()
        private val contacts = mutableListOf(TrustedContact("Mum", "Family", "123", "M", "backend-id"))

        private fun json(contact: TrustedContact): JsonElement = com.google.gson.JsonObject().apply {
            addProperty("_id", contact.id)
            addProperty("name", contact.name)
            addProperty("relation", contact.relation)
            addProperty("phone", contact.phone)
            addProperty("initials", contact.initials)
            contact.invitationStatus?.let { addProperty("invitationStatus", it.name) }
        }
        override suspend fun createInvitationContact(payload: JsonElement): Response<JsonElement> {
            creates++
            val p = payload.asJsonObject
            val contact = TrustedContact(p["name"].asString, p["relation"].asString, p["phone"].asString,
                p["initials"].asString, "new-$creates")
            contacts.add(contact)
            return Response.success(json(contact))
        }
        fun invitation(id: String, status: InvitationStatus = InvitationStatus.PENDING) = ContactInvitationResponse(
            id, "sender", "Friend", "Family", "123", "F", "2026-11-13T00:00:00Z", "recipient", status,
            "2026-11-13T00:00:00Z", id.padEnd(32, 'a'))
        override suspend fun sendInvitation(contactId: String, body: ContactInvitationRequest): Response<ContactInvitationResponse> {
            sends++
            if (failSend) return Response.error(503, "Unavailable".toResponseBody())
            val i = contacts.indexOfFirst { it.id == contactId }
            contacts[i] = contacts[i].copy(invitationStatus = InvitationStatus.PENDING)
            return Response.success(invitation(contactId))
        }
        override suspend fun getIncomingInvitations(): Response<IncomingInvitationsResponse> {
            incomingLoads++
            if (failIncoming) return Response.error(503, "Unavailable".toResponseBody())
            return Response.success(IncomingInvitationsResponse(incoming))
        }
        override suspend fun acceptInvitation(contactId: String, body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse> {
            if (failDecision) return Response.error(409, "Changed".toResponseBody())
            incoming = incoming.filterNot { it.id == contactId }
            return Response.success(invitation(contactId, InvitationStatus.ACCEPTED))
        }
        override suspend fun rejectInvitation(contactId: String, body: ContactInvitationDecisionRequest): Response<ContactInvitationResponse> {
            if (failDecision) return Response.error(409, "Changed".toResponseBody())
            incoming = incoming.filterNot { it.id == contactId }
            return Response.success(invitation(contactId, InvitationStatus.REJECTED))
        }
        override suspend fun getContacts(ownerId: String): Response<JsonElement> {
            calls.add("GET:$ownerId")
            loadGate?.await()
            if (failLoad) return Response.error(503, "Unavailable".toResponseBody())
            return Response.success(com.google.gson.JsonObject().apply {
                add("contacts", com.google.gson.JsonArray().apply { contacts.forEach { add(json(it)) } })
            })
        }
        override suspend fun addContact(payload: JsonElement): Response<Unit> {
            calls.add("POST:${payload.asJsonObject["ownerId"].asString}")
            if (failWrite) return Response.error(422, "Invalid contact".toResponseBody())
            return Response.success(Unit)
        }
        override suspend fun deleteContact(id: String): Response<Unit> {
            calls.add("DELETE:$id")
            contacts.removeAll { it.id == id }
            return Response.success<Unit>(204, null)
        }
    }
}
