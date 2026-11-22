package com.project.saaya.ui.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.project.saaya.ui.state.IncidentViewModel
import com.project.saaya.ui.state.LiveSafetyViewModel

val LocalIncidentViewModel = staticCompositionLocalOf<IncidentViewModel?> { null }
val LocalLiveSafetyViewModel = staticCompositionLocalOf<LiveSafetyViewModel?> { null }

/** One authenticated SOS host serves every existing SOS button and survives screen navigation. */
@Composable
fun IncidentSosHost(
    viewModel: IncidentViewModel,
    liveSafetyViewModel: LiveSafetyViewModel,
    onLiveSafetyRequested: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pendingLiveIncident by rememberSaveable { mutableStateOf<String?>(null) }
    val currentIncident by rememberUpdatedState(state.activeIncident)
    val openLive by rememberUpdatedState(onLiveSafetyRequested)
    
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val incidentId = pendingLiveIncident
                pendingLiveIncident = null
                if (incidentId != null && currentIncident?.id == incidentId) openLive(incidentId)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.clearSessionState() }
    }
    
    LaunchedEffect(viewModel) { viewModel.refresh() }
    
    LaunchedEffect(state.pendingCallNumber) {
        if (state.pendingCallNumber != null) {
            val incidentId = state.activeIncident?.id
            viewModel.consumeCall()
            if (incidentId != null) {
                openLive(incidentId)
            }
        }
    }

    CompositionLocalProvider(
        LocalIncidentViewModel provides viewModel,
        LocalLiveSafetyViewModel provides liveSafetyViewModel
    ) { content() }

    if (state.isLoading && !state.isRefreshing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (state.isStartingSos) "Starting SOS…" else "Updating incidents…") },
            text = {
                Row(horizontalArrangement = Arrangement.Start) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("Connecting to Saaya. Please wait.")
                }
            },
            confirmButton = {},
            dismissButton = null
        )
    }
    
    val error = state.error
    if (error != null && !state.isLoading) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("SOS") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            },
            dismissButton = if (state.fallbackCallNumber != null) ({
                val context = androidx.compose.ui.platform.LocalContext.current
                val callAction = rememberEmergencyCallAction(state.fallbackCallNumber.orEmpty()) { _ -> }
                TextButton(onClick = { 
                    viewModel.dismissError()
                    callAction()
                }) { Text("Call contact via Phone") }
            }) else null
        )
    }
}
