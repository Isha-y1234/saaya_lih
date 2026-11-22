package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.saaya.data.model.Incident
import com.project.saaya.data.remote.NotificationApiService
import kotlinx.coroutines.CancellationException

/** Read-only recipient view; the server checks the incident's notification grant and current contact link. */
@Composable
fun IncidentAlertScreen(incidentId: String, api: NotificationApiService, onBack: () -> Unit,
                        onWatchLive: () -> Unit = {}, onChat: () -> Unit = {}, onEvidence: () -> Unit = {}, onReports: () -> Unit = {}) {
    var incident by remember(incidentId) { mutableStateOf<Incident?>(null) }
    var loading by remember(incidentId) { mutableStateOf(true) }
    var error by remember(incidentId) { mutableStateOf<String?>(null) }
    var retry by remember(incidentId) { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, incidentId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) retry++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(incidentId, retry) {
        loading = true
        error = null
        incident = null
        try {
            val response = api.getAlert(incidentId)
            if (!response.isSuccessful) {
                error = if (response.code() == 404) "This incident is unavailable or is not shared with your account."
                else "Could not load this incident. Please retry."
            } else {
                incident = response.body()?.takeIf { it.id == incidentId }
                if (incident == null) error = "Could not load this incident. Please retry."
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load this incident. Check your connection and retry." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("SOS alert", style = MaterialTheme.typography.headlineSmall)
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!loading) TextButton(onClick = { retry++ }) { Text(if (error != null) "Retry" else "Refresh") }
        if (!loading && error == null) incident?.let {
            Text("Incident: ${it.id}")
            Text("Status: ${it.status}")
            Text("Started: ${it.startedAt}")
            it.endedAt?.let { ended -> Text("Ended: $ended") }
            if (it.latitude != null && it.longitude != null) Text("Location: ${it.latitude}, ${it.longitude}")
            Text("Shared SOS incident")
            TextButton(onClick = onChat) { Text("Open incident chat") }
            TextButton(onClick = onEvidence) { Text("Emergency evidence") }
            TextButton(onClick = onReports) { Text("Incident Reports") }
            if (it.status == "ACTIVE") TextButton(onClick = onWatchLive) { Text("Watch live safety") }
        }
    }
}
