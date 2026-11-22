package com.project.saaya.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.saaya.data.model.TrustedContact
import com.project.saaya.data.remote.ContactInvitationResponse
import com.project.saaya.data.remote.InvitationStatus
import com.project.saaya.ui.sos.rememberEmergencyCallAction
import com.project.saaya.ui.state.TrustedCircleMemberForm
import com.project.saaya.ui.state.TrustedCircleUiState
import com.project.saaya.ui.state.TrustedCircleViewModel
import com.project.saaya.ui.theme.SaayaBlack
import com.project.saaya.ui.theme.SaayaBorder
import com.project.saaya.ui.theme.SaayaTextSecondary
import com.project.saaya.ui.theme.SaayaWhite

private val CircleAccent = Color(0xFF75617F)
private val CircleTint = Color(0xFFF2EEF6)

@Composable
fun TrustedCircleScreen(onBack: () -> Unit, trustedCircleViewModel: TrustedCircleViewModel) {
    val state by trustedCircleViewModel.uiState.collectAsState()
    LaunchedEffect(trustedCircleViewModel) {
        if (!trustedCircleViewModel.uiState.value.isLoading) trustedCircleViewModel.loadContacts()
    }
    TrustedCircleContent(
        state = state, onBack = onBack, onRefresh = { trustedCircleViewModel.refresh() },
        onSend = { id, contact, done -> trustedCircleViewModel.sendInvitation(id, contact, done) },
        onSaveContact = { contact, done -> trustedCircleViewModel.addContact(contact, done) },
        onDelete = { trustedCircleViewModel.deleteContact(it) },
        onAccept = { trustedCircleViewModel.acceptInvitation(it) },
        onReject = { trustedCircleViewModel.rejectInvitation(it) }
    )
}

@Composable
internal fun TrustedCircleContent(
    state: TrustedCircleUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String, TrustedContact, () -> Unit) -> Unit,
    onSaveContact: (TrustedContact, () -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onAccept: (ContactInvitationResponse) -> Unit,
    onReject: (ContactInvitationResponse) -> Unit
) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    var existingId by rememberSaveable { mutableStateOf("") }
    var saayaId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var callError by remember { mutableStateOf<String?>(null) }
    val accepted = state.contacts.filter { it.invitationStatus == InvitationStatus.ACCEPTED }
    val outgoing = state.contacts.filter { it.invitationStatus != null && it.invitationStatus != InvitationStatus.ACCEPTED }
    val phoneContacts = state.contacts.filter { it.invitationStatus == null }
    val openForm: (TrustedContact?) -> Unit = { contact ->
        existingId = contact?.id.orEmpty()
        name = contact?.name.orEmpty()
        relation = contact?.relation.orEmpty()
        phone = contact?.phone.orEmpty()
        saayaId = ""
        showForm = true
    }

    Scaffold(containerColor = SaayaWhite) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = SaayaBlack)
                }
                Column(Modifier.weight(1f)) {
                    Text("Trusted Circle", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = SaayaBlack)
                    Text("${accepted.size} accepted members", fontSize = 12.sp, color = SaayaTextSecondary)
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(Icons.Outlined.Refresh, "Refresh Trusted Circle", tint = CircleAccent)
                }
            }
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth()
                .semantics { contentDescription = "Updating Trusted Circle" }, color = CircleAccent)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Surface(color = Color(0xFF30302F), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.PeopleOutline, null, tint = Color(0xFFE8DED0))
                            Text("Your people, close by", color = SaayaWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("Invite a Saaya account to your circle and keep their emergency number within reach.",
                                color = SaayaWhite.copy(alpha = 0.78f), fontSize = 13.sp)
                            Button(onClick = { openForm(null) }, enabled = !state.isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = CircleTint, contentColor = SaayaBlack),
                                shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add member")
                            }
                        }
                    }
                }
                state.successMessage?.let { message -> item { CircleMessage(message, false) } }
                state.error?.let { message -> item {
                    CircleMessage(message, true)
                    TextButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Refresh and retry", color = CircleAccent) }
                } }
                callError?.let { message -> item { CircleMessage(message, true) } }
                item { CircleSectionTitle("Accepted members", accepted.size) }
                if (accepted.isEmpty() && !state.isLoading) item {
                    CircleEmpty("No accepted members yet", "Members appear here after they accept your invitation.")
                }
                items(accepted, key = { "accepted:${it.id}" }) { contact ->
                    CircleContactCard(contact, !state.isLoading, onRemove = { removeId = contact.id },
                        onInvite = null, onCallError = { callError = it })
                }
                if (outgoing.isNotEmpty()) {
                    item { CircleSectionTitle("Outgoing invitations", outgoing.size) }
                    items(outgoing, key = { "outgoing:${it.id}" }) { contact ->
                        CircleContactCard(contact, !state.isLoading, onRemove = { removeId = contact.id },
                            onInvite = if (contact.invitationStatus != InvitationStatus.PENDING) ({ openForm(contact) }) else null,
                            onCallError = { callError = it })
                    }
                }
                if (phoneContacts.isNotEmpty()) {
                    item { CircleSectionTitle("Saved phone contacts", phoneContacts.size) }
                    items(phoneContacts, key = { "phone:${it.id}" }) { contact ->
                        CircleContactCard(contact, !state.isLoading, onRemove = { removeId = contact.id },
                            onInvite = { openForm(contact) }, onCallError = { callError = it })
                    }
                }
                item { CircleSectionTitle("Incoming invitations", state.incomingInvitations.size) }
                if (state.incomingInvitations.isEmpty() && !state.isLoading) item {
                    CircleEmpty("You're all caught up", "New invitations to join someone's circle will appear here.")
                }
                items(state.incomingInvitations, key = { "incoming:${it.invitationId}" }) { invitation ->
                    Surface(shape = RoundedCornerShape(16.dp), color = SaayaWhite, border = BorderStroke(1.dp, SaayaBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Circle invitation", fontWeight = FontWeight.SemiBold, color = SaayaBlack)
                            // The API supplies the sender account ID, not their display name.
                            Text("From account ?${invitation.ownerId.takeLast(8)}", color = SaayaTextSecondary, fontSize = 12.sp)
                            Text("Invited as ${invitation.name}. Accept to join the sender's circle.", fontSize = 13.sp,
                                color = SaayaTextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAccept(invitation) }, enabled = !state.isLoading,
                                    colors = ButtonDefaults.buttonColors(containerColor = CircleAccent)) { Text("Accept") }
                                OutlinedButton(onClick = { onReject(invitation) }, enabled = !state.isLoading) { Text("Reject") }
                            }
                        }
                    }
                }
                item {
                    Text("Membership is directional. Accepting an invitation joins the sender's circle.",
                        color = SaayaTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
    if (showForm) {
        val form = TrustedCircleMemberForm(saayaId, phone, name, relation)
        AlertDialog(onDismissRequest = { if (!state.isLoading) showForm = false },
            title = { Text(if (existingId.isBlank()) "Add member" else "Invite saved contact") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ask your contact for their Saaya ID. They'll need to accept your invitation.",
                        color = SaayaTextSecondary, fontSize = 13.sp)
                    OutlinedTextField(saayaId, { saayaId = it }, label = { Text("Saaya ID") }, singleLine = true,
                        enabled = !state.isLoading, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        supportingText = { Text("Copy the full SAAYA- ID from your contact") })
                    OutlinedTextField(phone, { phone = it }, label = { Text("Emergency mobile number") }, singleLine = true,
                        enabled = !state.isLoading, readOnly = existingId.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = { Text(if (existingId.isBlank()) "Include the country code, e.g. +91" else "Your saved emergency number") })
                    OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true,
                        enabled = !state.isLoading && existingId.isBlank(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(relation, { relation = it }, label = { Text("Relationship (optional)") }, singleLine = true,
                        enabled = !state.isLoading && existingId.isBlank(), modifier = Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (state.isLoading) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = CircleAccent, strokeWidth = 2.dp)
                        Text("Saving?", fontSize = 12.sp)
                    }
                    if (existingId.isBlank()) TextButton(onClick = {
                        onSaveContact(form.contact()) { showForm = false }
                    }, enabled = form.canSaveContact && !state.isLoading) { Text("Save phone contact only", color = CircleAccent) }
                }
            },
            confirmButton = {
                Button(onClick = { onSend(form.normalizedSaayaId, form.contact(existingId)) { showForm = false } },
                    enabled = form.canSend && !state.isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = CircleAccent)) { Text("Send invitation") }
            },
            dismissButton = { TextButton(onClick = { showForm = false }, enabled = !state.isLoading) { Text("Cancel") } })
    }
    removeId?.let { id ->
        val contact = state.contacts.firstOrNull { it.id == id }
        AlertDialog(onDismissRequest = { if (!state.isLoading) removeId = null },
            title = { Text("Remove contact?") },
            text = { Text("Remove ${contact?.name ?: "this contact"} and their saved emergency number from your circle?") },
            confirmButton = { TextButton(onClick = { onDelete(id); removeId = null }, enabled = !state.isLoading) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeId = null }, enabled = !state.isLoading) { Text("Cancel") } })
    }
}

@Composable
private fun CircleContactCard(contact: TrustedContact, enabled: Boolean, onRemove: () -> Unit,
                              onInvite: (() -> Unit)?, onCallError: (String) -> Unit) {
    // Reuse direct calling with its permission/dialler fallback; this never starts SOS.
    val call = rememberEmergencyCallAction(contact.phone, onCallError)
    Surface(shape = RoundedCornerShape(16.dp), color = SaayaWhite, border = BorderStroke(1.dp, SaayaBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(40.dp), shape = CircleShape, color = CircleTint) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(contact.initials, color = CircleAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(contact.name, fontWeight = FontWeight.SemiBold, color = SaayaBlack, fontSize = 15.sp)
                    Text(contact.relation, color = SaayaTextSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = call) { Icon(Icons.Outlined.Call, "Call ${contact.name}", tint = Color(0xFF52705E)) }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, "Remove ${contact.name}", tint = SaayaTextSecondary)
                }
            }
            Text(contact.phone, color = SaayaBlack, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val status = when (contact.invitationStatus) {
                    InvitationStatus.PENDING -> "Pending"
                    InvitationStatus.ACCEPTED -> "Accepted"
                    InvitationStatus.REJECTED -> "Rejected"
                    InvitationStatus.REVOKED -> "Revoked"
                    null -> "Phone contact"
                }
                val accepted = contact.invitationStatus == InvitationStatus.ACCEPTED
                Surface(shape = RoundedCornerShape(8.dp), color = if (accepted) Color(0xFFECF3EE) else CircleTint) {
                    Text(status, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (accepted) Color(0xFF466852) else CircleAccent, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                onInvite?.let { invite -> TextButton(onClick = invite, enabled = enabled) { Text("Invite", color = CircleAccent) } }
            }
        }
    }
}

@Composable
private fun CircleSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = SaayaBlack, fontSize = 16.sp)
        Text(count.toString(), color = SaayaTextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun CircleEmpty(title: String, description: String) {
    Surface(color = Color(0xFFF7F5F3), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = SaayaBlack, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(description, color = SaayaTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CircleMessage(message: String, error: Boolean) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (error) Color(0xFFFAEFED) else Color(0xFFECF3EE)) {
        Text(message, Modifier.fillMaxWidth().padding(14.dp),
            color = if (error) MaterialTheme.colorScheme.error else Color(0xFF466852), fontSize = 13.sp)
    }
}
