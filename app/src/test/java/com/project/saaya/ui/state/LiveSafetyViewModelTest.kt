package com.project.saaya.ui.state

import com.project.saaya.data.model.IncidentsResponse
import com.project.saaya.data.remote.LiveSafetyApiService
import com.project.saaya.data.remote.LiveSafetyJoin
import com.project.saaya.data.remote.LiveSafetyStatus
import com.project.saaya.data.remote.LiveSafetyViewer
import kotlinx.coroutines.flow.MutableStateFlow
import com.project.saaya.data.repository.IncidentChatRepository
import com.project.saaya.data.repository.LiveSafetyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSafetyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeApi()
    private val media = FakeMedia()
    private lateinit var model: LiveSafetyViewModel
    private val id = "507f1f77bcf86cd799439011"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val retrofit = Retrofit.Builder()
            .baseUrl("https://test.saaya.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        model = LiveSafetyViewModel(
            LiveSafetyRepository(api),
            IncidentChatRepository(retrofit),
            media
        )
    }
    @After fun cleanup() { model.leave(); Dispatchers.resetMain() }

    @Test fun viewerSubscribesWithoutCameraOrMicrophonePermission() = runTest(dispatcher) {
        model.join(id, publishingPermission = false)
        runCurrent()
        assertTrue(model.uiState.value.joined)
        assertFalse(media.publishingVideo)
        assertFalse(media.publishingAudio)
        assertEquals(1, api.joins)
        model.leave()
        assertFalse(model.uiState.value.joined)
        assertTrue(media.disconnected)
    }

    @Test fun senderNeedsBothPermissionsBeforeRequestingToken() = runTest(dispatcher) {
        api.role = "publisher"
        model.join(id, publishingPermission = false)
        runCurrent()
        assertEquals(0, api.joins)
        assertFalse(model.uiState.value.joined)
        assertNotNull(model.uiState.value.error)

        model.join(id, publishingPermission = true)
        runCurrent()
        assertTrue(media.publishingVideo)
        assertTrue(media.publishingAudio)
        assertTrue(model.uiState.value.joined)
        model.leave()
    }

    @Test fun endedIncidentCannotJoinAndEndingDisconnectsAnExistingViewer() = runTest(dispatcher) {
        api.active = false
        model.join(id, true)
        runCurrent()
        assertTrue(model.uiState.value.ended)
        assertEquals(0, api.joins)

        api.active = true
        model.join(id, true)
        runCurrent()
        api.active = false
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(model.uiState.value.ended)
        assertTrue(media.disconnected)
    }

    @Test fun lostAuthorizationFailsClosedWithoutLeakingToken() = runTest(dispatcher) {
        model.join(id, true)
        runCurrent()
        api.denied = true
        advanceTimeBy(5001)
        runCurrent()
        assertFalse(model.uiState.value.joined)
        assertTrue(media.disconnected)
        assertFalse(model.uiState.value.error.orEmpty().contains("test-media-token"))
    }

    @Test fun mediaFailureIsRecoverableAndDoesNotExposeSdkError() = runTest(dispatcher) {
        media.fail = true
        model.join(id, true)
        runCurrent()
        assertFalse(model.uiState.value.joined)
        assertFalse(model.uiState.value.error.orEmpty().contains("test-media-token"))
        media.fail = false
        model.join(id, true)
        runCurrent()
        assertTrue(model.uiState.value.joined)
        model.leave()
    }

    @Test fun mismatchedIncidentCannotSupplyConnectionCredentials() = runTest(dispatcher) {
        api.responseId = "different-incident"
        model.join(id, true)
        runCurrent()
        assertEquals(0, api.joins)
        assertFalse(model.uiState.value.joined)
    }

    @Test fun viewerMicrophoneTogglesInPlaceWithoutCameraOrReconnect() = runTest(dispatcher) {
        model.join(id, false)
        runCurrent()
        model.setViewerMicrophoneEnabled(true, false)
        runCurrent()
        assertFalse(media.publishingAudio)
        assertEquals(0, api.audioUpdates)
        model.setViewerMicrophoneEnabled(true, true)
        runCurrent()
        assertTrue(media.publishingAudio)
        assertTrue(model.uiState.value.microphoneEnabled)
        assertFalse(media.publishingVideo)
        model.setViewerMicrophoneEnabled(false, false)
        runCurrent()
        assertFalse(media.publishingAudio)
        assertFalse(model.uiState.value.microphoneEnabled)
        assertEquals(1, api.joins)
        assertEquals(1, api.audioUpdates)
        assertEquals(1, media.connections)
        assertFalse(media.disconnected)
        assertTrue(model.uiState.value.joined)
        model.leave()
    }

    @Test fun microphoneFailurePreservesConnectedVideoAndSenderIsUnchanged() = runTest(dispatcher) {
        model.join(id, false)
        runCurrent()
        media.fail = true
        model.setViewerMicrophoneEnabled(true, true)
        runCurrent()
        assertTrue(model.uiState.value.joined)
        assertFalse(media.disconnected)
        assertNotNull(model.uiState.value.microphoneError)
        assertFalse(model.uiState.value.microphoneEnabled)
        model.leave()
        media.fail = false
        api.role = "publisher"
        model.join(id, true)
        runCurrent()
        val updates = api.audioUpdates
        model.setViewerMicrophoneEnabled(false, true)
        runCurrent()
        assertEquals(updates, api.audioUpdates)
        assertTrue(media.publishingAudio)
        assertTrue(media.publishingVideo)
        model.leave()
    }

    @Test fun historyRejoinRequiresOpenSessionAndNeverUsesNormalJoin() = runTest(dispatcher) {
        api.sessionActive = false
        model.join(id, false, rejoinOnly = true)
        runCurrent()
        assertTrue(model.uiState.value.ended)
        assertEquals(0, api.rejoins)
        assertEquals(0, api.joins)
        api.sessionActive = true
        model.join(id, false, rejoinOnly = true)
        runCurrent()
        assertTrue(model.uiState.value.joined)
        assertEquals(1, api.rejoins)
        assertEquals(0, api.joins)
        assertFalse(media.publishingVideo)
        assertFalse(media.publishingAudio)
        api.sessionActive = false
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(model.uiState.value.ended)
        assertTrue(media.disconnected)
        model.leave()
    }

    @Test fun microphoneIndicatorFollowsTrackEventsWithoutRejoining() = runTest(dispatcher) {
        model.join(id, false)
        runCurrent()
        media.microphoneEnabled.value = true
        runCurrent()
        assertTrue(model.uiState.value.microphoneEnabled)
        media.microphoneEnabled.value = false
        runCurrent()
        assertFalse(model.uiState.value.microphoneEnabled)
        assertEquals(1, api.joins)
        assertEquals(1, media.connections)
        assertFalse(media.disconnected)
        model.leave()
    }

    @Test fun connectedRosterExcludesUnconnectedAndUnauthorizedPeers() {
        val alice = LiveSafetyViewer("alice", "Alice")
        val bob = LiveSafetyViewer("bob", "Bob")
        val roster = listOf(bob, alice, alice, LiveSafetyViewer("not-connected", "Pending"))
        assertEquals(listOf(alice, bob), connectedViewers(setOf("alice", "bob", "unknown"), roster))
        assertEquals(listOf(bob), connectedViewers(setOf("bob"), roster))
        assertTrue(connectedViewers(emptySet(), roster).isEmpty())
        assertTrue(connectedViewers(setOf("alice"), emptyList()).isEmpty())
    }

    @Test fun rosterRefreshDoesNotRecreateRoomOrChangeSenderMedia() = runTest(dispatcher) {
        api.role = "publisher"
        model.join(id, true)
        runCurrent()
        api.viewers = listOf(LiveSafetyViewer("viewer", "Viewer"))
        model.refreshViewers()
        runCurrent()
        assertEquals(api.viewers, model.uiState.value.viewers)
        assertEquals(1, api.joins)
        assertEquals(1, media.connections)
        assertFalse(media.disconnected)
        assertTrue(media.publishingAudio)
        assertTrue(media.publishingVideo)
        api.viewers = emptyList()
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(model.uiState.value.viewers.isEmpty())
        model.leave()
    }

    private class FakeApi : LiveSafetyApiService {
        var role = "viewer"
        var active = true
        var sessionActive = true
        var viewers: List<LiveSafetyViewer> = emptyList()
        var rejoins = 0
        var joins = 0
        var audioUpdates = 0
        var denied = false
        var responseId = "507f1f77bcf86cd799439011"
        override suspend fun enableViewerAudio(incidentId: String): Response<Unit> {
            audioUpdates++
            return Response.success(Unit)
        }
        override suspend fun activeIncidents() = Response.success(IncidentsResponse(emptyList()))
        override suspend fun status(incidentId: String, verifySession: Boolean): Response<LiveSafetyStatus> =
            if (denied) Response.error(404, "Unavailable".toResponseBody())
            else Response.success(LiveSafetyStatus(responseId, active, "configured", role, sessionActive, viewers))
        override suspend fun rejoin(incidentId: String): Response<LiveSafetyJoin> {
            rejoins++
            return Response.success(LiveSafetyJoin(responseId, "wss://test-only.livekit.cloud", "test-media-token", role, 120))
        }
        override suspend fun join(incidentId: String): Response<LiveSafetyJoin> {
            joins++
            return Response.success(LiveSafetyJoin(responseId, "wss://test-only.livekit.cloud", "test-media-token", role, 120))
        }
    }
    private class FakeMedia : SafetyMedia {
        var connections = 0
        override val microphoneEnabled = MutableStateFlow(false)
        var publishingVideo = false
        var publishingAudio = false
        var disconnected = false
        var fail = false
        override suspend fun connect(url: String, token: String, video: Boolean, audio: Boolean) {
            if (fail) error("test-media-token")
            connections++
            publishingVideo = video
            publishingAudio = audio
            microphoneEnabled.value = audio
            disconnected = false
        }
        override suspend fun setViewerMicrophoneEnabled(enabled: Boolean) {
            if (fail) error("test-media-token")
            publishingAudio = enabled
            microphoneEnabled.value = enabled
        }
        override fun disconnect() { disconnected = true; microphoneEnabled.value = false }
        override fun release() { disconnected = true }
    }
}
