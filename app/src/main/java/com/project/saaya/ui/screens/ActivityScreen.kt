package com.project.saaya.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.location.Location
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.project.saaya.ui.theme.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.project.saaya.data.model.UserProfile
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.ActivityEvidenceRepository
import com.project.saaya.data.repository.ActivityAiReport
import com.project.saaya.data.repository.activityCoordinates
import com.project.saaya.data.repository.NativeWavRecorder
import com.project.saaya.data.repository.activityExtension
import com.project.saaya.ui.components.HomeTab
import com.project.saaya.ui.components.SaayaBottomNavigationBar
import com.project.saaya.ui.components.SaayaTopBar
import com.project.saaya.ui.components.SmallSosButton
import com.project.saaya.ui.sos.rememberSosCallAction
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private fun captureTime(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

private fun displayTime(value: String): String {
    val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val date = runCatching { input.parse(value.take(19)) }.getOrNull() ?: return value
    return java.text.DateFormat.getDateTimeInstance().format(date)
}

private fun mediaMaximum(kind: String): Long = when (kind) {
    "photo" -> 10L * 1024 * 1024
    "video" -> 50L * 1024 * 1024
    else -> 960044
}

@Composable
fun ActivityScreen(
    userProfile: UserProfile,
    userId: String,
    api: ActivityEvidenceApi,
    onHomeClick: () -> Unit,
    onJourneyClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onProfileClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onChatClick: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context, userId, api) { ActivityEvidenceRepository(context, userId, api) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioStop = remember(userId) { AtomicBoolean(false) }
    var saving by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(emptyList<ActivityEvidenceRepository.Entry>()) }
    var legacy by remember { mutableStateOf(emptyList<ActivityItem>()) }
    val saved = pending.filter { it.queued }.map { it.item() } +
        legacy.filter { remote -> pending.none { it.capture.requestId == remote.requestId } }
    var cursor by remember { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var reportId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var consent by rememberSaveable(userId, selectedId) { mutableStateOf(false) }
    var provider by rememberSaveable(userId, selectedId) { mutableStateOf("cloudflare") }
    var availableProvider by remember(userId, selectedId) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId, api) {
        if (selectedId != null) {
            try { availableProvider = api.config().synthesisProvider }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { availableProvider = null }
            if (provider != "cloudflare" && provider != availableProvider) consent = false
        }
    }
    var preview by remember(userId, selectedId) { mutableStateOf<File?>(null) }
    var archiveTarget by remember { mutableStateOf<ActivityItem?>(null) }
    var selectedLocal by remember(userId) { mutableStateOf<ActivityCapture?>(null) }
    var localPreview by remember(userId) { mutableStateOf<ActivityCapture?>(null) }
    var discardTarget by remember(userId) { mutableStateOf<ActivityCapture?>(null) }
    var draftId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var draftKind by rememberSaveable(userId) { mutableStateOf("note") }
    var draftTime by rememberSaveable(userId) { mutableStateOf("") }
    var draftOpen by rememberSaveable(userId) { mutableStateOf(false) }
    var details by rememberSaveable(userId) { mutableStateOf("") }
    var latitude by rememberSaveable(userId) { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable(userId) { mutableStateOf<Double?>(null) }
    var analysisProgress by remember { mutableStateOf<String?>(null) }

    val callSosContact = rememberSosCallAction(phoneNumber = userProfile.sosContactNumber, onError = { status = it })

    suspend fun refresh(before: String? = null) {
        pending = repository.pending()
        val page = try { api.list(before) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return }
        legacy = ((if (before == null) emptyList() else legacy) + page.items).distinctBy { it.id }
        cursor = page.nextBefore
    }

    fun perform(message: String, action: suspend () -> Unit) {
        if (saving) return
        saving = true
        status = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: HttpException) {
                status = when (error.code()) {
                    401, 403 -> "Sign in again to access your Activity."
                    404 -> "This Activity action is unavailable on the server. Local captures are still saved."
                    413 -> "This capture exceeds the server size limit."
                    429 -> "Activity analysis is busy. Try again later."
                    else -> message
                }
            }
            catch (_: OutOfMemoryError) { status = "This device could not process the media. The capture is preserved." }
            catch (_: Exception) { status = message }
            finally { saving = false }
        }
    }

    fun newDraft(kind: String): String {
        val id = UUID.randomUUID().toString()
        draftId = id
        draftKind = kind
        draftTime = captureTime()
        details = ""
        latitude = null
        longitude = null
        return id
    }

    val exportReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val item = saved.firstOrNull { it.id == exportId }
        exportId = null
        if (uri != null && item != null) perform("Could not save the AI report PDF.") {
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("activity-report-", ".pdf", context.cacheDir)
                try {
                    temp.outputStream().use { ActivityAiReport.write(item, it) }
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        temp.inputStream().use { it.copyTo(output) }
                    }
                } finally { temp.delete() }
            }
            status = "AI report PDF saved."
        }
    }

    fun downloadReport(item: ActivityItem) {
        exportId = item.id
        exportReport.launch("SAAYA-Activity-${item.id.takeLast(8)}.pdf")
    }

    fun captureTemp(id: String, kind: String) = File(context.cacheDir,
        "activity-capture/$userId/$id.${activityExtension(kind)}")

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = draftId
        val kind = draftKind
        if (id != null && (kind == "photo" || kind == "video")) {
            val temp = captureTemp(id, kind)
            if (result.resultCode != Activity.RESULT_OK) {
                temp.delete()
                draftId = null
            } else perform("Could not save camera output. Try capturing again.") {
                val destination = repository.media(id, kind)
                try {
                    withContext(Dispatchers.IO) {
                        val input = if (temp.length() > 0) temp.inputStream()
                        else result.data?.data?.let(context.contentResolver::openInputStream)
                        requireNotNull(input).use { source ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var size = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    check(size <= mediaMaximum(kind))
                                    output.write(buffer, 0, count)
                                }
                                check(size > 0)
                            }
                        }
                    }
                    repository.save(ActivityCapture(id, kind, draftTime, fileSize = destination.length()))
                    pending = repository.pending()
                    draftOpen = true
                } catch (error: Exception) {
                    destination.delete()
                    throw error
                } finally {
                    temp.delete()
                }
            }
        }
    }

    fun launchCamera(kind: String) {
        val id = newDraft(kind)
        val temp = captureTemp(id, kind)
        try {
            temp.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.evidence", temp)
            val intent = Intent(if (kind == "photo") MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("Activity capture", uri)
            if (kind == "video") {
                intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
                intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, mediaMaximum(kind))
            }
            camera.launch(intent)
        } catch (_: Exception) {
            temp.delete()
            draftId = null
            status = "Camera unavailable on this device."
        }
    }

    var requestedCamera by rememberSaveable { mutableStateOf("photo") }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) launchCamera(requestedCamera) else status = "Camera permission is required to capture media."
    }

    fun openCamera(kind: String) {
        if (saving || recording) return
        requestedCamera = kind
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(kind)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun recordAudio() {
        val id = newDraft("audio")
        val capturedAt = draftTime
        val file = repository.media(id, "audio")
        audioStop.set(false)
        recording = true
        scope.launch {
            try {
                NativeWavRecorder().record(file, audioStop)
                check(file.length() > 44)
                repository.save(ActivityCapture(id, "audio", capturedAt, fileSize = file.length()))
                pending = repository.pending()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) draftOpen = true
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (_: Exception) {
                file.delete()
                draftId = null
                status = "Could not record audio. Check microphone access and try again."
            } finally { recording = false }
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) recordAudio() else status = "Microphone permission is required to record audio."
    }

    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val id = draftId
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val priority = if (precise) com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
                else com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            client.getCurrentLocation(priority, null)
                .addOnSuccessListener { location: Location? ->
                    if (draftId == id && draftOpen) {
                        latitude = location?.latitude
                        longitude = location?.longitude
                        if (location == null) status = "Location unavailable. You can save without it."
                    }
                }.addOnFailureListener { status = "Location unavailable. You can save without it." }
        }.onFailure { status = "Location unavailable. You can save without it." }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.any { granted -> granted }) requestLocation() else status = "Location permission was declined."
    }

    DisposableEffect(lifecycle, audioStop) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) audioStop.set(true)
        }
        lifecycle.addObserver(observer)
        onDispose { audioStop.set(true); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository) { refresh() }

    fun saveDraft() {
        val id = draftId ?: return
        val size = if (draftKind == "note") 0 else repository.media(id, draftKind).length()
        val capture = ActivityCapture(id, draftKind, draftTime, details, latitude, longitude, size)
        perform("Could not save capture details.") {
            repository.save(capture, queued = true)
            pending = repository.pending()
            draftOpen = false
        }
    }

    Scaffold(
        containerColor = SaayaWhite,
        bottomBar = {
            SaayaBottomNavigationBar(
                selectedTab = HomeTab.Activity,
                onTabSelected = { tab ->
                    when (tab) {
                        HomeTab.Home -> onHomeClick()
                        HomeTab.Journey -> onJourneyClick()
                        HomeTab.Network -> onNetworkClick()
                        HomeTab.Activity -> Unit
                        HomeTab.Profile -> onProfileClick()
                    }
                }
            )
        },
        floatingActionButton = {
            SmallSosButton(
                onClick = {
                    if (userProfile.sosContactNumber.isBlank()) {
                        onProfileClick()
                    } else {
                        callSosContact()
                    }
                }
            )
        },
        floatingActionButtonPosition = FabPosition.Start
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SaayaTopBar(
                onTrustedCircleClick = onTrustedCircleClick,
                onChatClick = onChatClick
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ACTIVITY",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "Capture what you notice. Keep a record of what happened.",
                        fontSize = 11.sp,
                        color = SaayaTextSecondary
                    )
                }

                item {
                    Text(
                        text = "RECORD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        EvidenceCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.CameraAlt,
                            title = "Camera",
                            description = "Take a photo",
                            iconColor = Color(0xFF4F7197),
                            iconBackground = Color(0xFFEAF0F6),
                            onClick = {
                                openCamera("photo")
                            }
                        )
                        EvidenceCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Videocam,
                            title = "Video",
                            description = "Record video",
                            iconColor = Color(0xFF77628E),
                            iconBackground = Color(0xFFF0ECF5),
                            onClick = {
                                openCamera("video")
                            }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        EvidenceCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Mic,
                            title = "Audio",
                            description = if (recording) "Tap to stop recording" else "Record sound",
                            iconColor = Color(0xFF9A7447),
                            iconBackground = Color(0xFFF6F0E7),
                            onClick = {
                                if (saving) return@EvidenceCard
                                if (recording) audioStop.set(true)
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recordAudio()
                                else mic.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        )
                        EvidenceCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Description,
                            title = "Note",
                            description = "Write details",
                            iconColor = Color(0xFF4F8078),
                            iconBackground = Color(0xFFEAF2F0),
                            onClick = {
                                if (!saving && !recording) {
                                    newDraft("note")
                                    draftOpen = true
                                }
                            }
                        )
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFAFAFA),
                        border = BorderStroke(1.dp, SaayaBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccessTime,
                                contentDescription = null,
                                tint = SaayaTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Details are added after capture",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SaayaBlack
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Capture time is saved. Location is optional.",
                                    fontSize = 9.sp,
                                    color = SaayaTextSecondary
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "RECENT ACTIVITY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaayaBlack
                    )
                }

                item {
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let { Text(it, fontSize = 10.sp) }
                    if (!saving && pending.isEmpty() && saved.isEmpty()) {
                        Text("Saved captures will appear here.", fontSize = 10.sp, color = SaayaTextSecondary)
                    }
                    TextButton(enabled = !saving, onClick = { perform("Could not refresh Activity.") { refresh() } }) { Text("Refresh", fontSize = 10.sp) }
                }
                items(pending.filter { !it.queued }, key = { it.capture.requestId }) { entry ->
                    val capture = entry.capture
                    ActivityRecordCard(
                        record = activityRecord(capture.kind, capture.details, capture.capturedAt, capture.latitude, "Draft · finish details"),
                        onClick = { selectedLocal = capture }
                    )
                }
                items(saved, key = { it.id }) { item ->
                    Column {
                        ActivityRecordCard(
                            record = activityRecord(item.kind, item.details, item.capturedAt, item.latitude,
                                if (item.id == item.requestId) "Saved on phone" else "Previously uploaded"),
                            onClick = { selectedId = item.id }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.analysis.hasActivityReport()) {
                                TextButton(enabled = !saving, onClick = { reportId = item.id }) { Text("View AI Report") }
                                TextButton(enabled = !saving, onClick = { downloadReport(item) }) { Text("Download AI Report") }
                            } else {
                                TextButton(enabled = !saving, onClick = { selectedId = item.id }) {
                                    Text(if (item.analysis?.state in setOf("PAUSED", "FAILED")) "Retry AI analysis" else "Analyse with AI")
                                }
                            }
                        }
                    }
                }
                cursor?.let { next ->
                    item { TextButton(enabled = !saving, onClick = { perform("Could not load more captures.") { refresh(next) } }) { Text("Load more") } }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = SaayaWhite,
                        border = BorderStroke(1.dp, SaayaBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.GraphicEq,
                                    contentDescription = null,
                                    tint = SaayaTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "PATTERN INSIGHTS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SaayaTextSecondary
                                )
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = "Review AI observations on individual saved captures.",
                                fontSize = 10.sp,
                                color = SaayaBlack
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "AI observations are unverified.",
                                fontSize = 9.sp,
                                color = SaayaTextSecondary
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(15.dp))
                }
            }
        }
    }

    if (draftOpen) AlertDialog(
            onDismissRequest = { if (!saving) draftOpen = false },
        title = { Text(if (draftKind == "note") "Note" else "Capture details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayTime(draftTime))
                OutlinedTextField(value = details, onValueChange = { details = it.take(2000) },
                    label = { Text(if (draftKind == "note") "Note" else "Description (optional)") })
                activityCoordinates(latitude, longitude)?.let { Text(it) }
                status?.let { Text(it) }
                TextButton(onClick = {
                    if (latitude != null) { latitude = null; longitude = null }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        requestLocation()
                    else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(if (latitude == null) "Attach current location" else "Remove location") }
            }
        },
        confirmButton = {
                TextButton(enabled = !saving && (draftKind != "note" || details.isNotBlank()),
                    onClick = ::saveDraft) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { draftOpen = false }) { Text("Close") } }
    )

    saved.firstOrNull { it.id == selectedId }?.let { item ->
        val job = item.analysis
        AlertDialog(
            onDismissRequest = { if (!saving) selectedId = null },
            title = { Text(item.kind.replaceFirstChar { it.titlecase() }) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayTime(item.capturedAt))
                    if (item.details.isNotBlank()) SelectionContainer { Text("User description\n${item.details}") }
                    activityCoordinates(item.latitude, item.longitude)?.let { Text(it) }
                    if (item.mediaType != null) {
                        TextButton(enabled = !saving, onClick = { perform("Could not open this private capture.") { preview = repository.preview(item) } }) {
                            Text("Load preview")
                        }
                        preview?.let { ActivityPreview(it, item.kind) }
                    }
                    Text(analysisStatus(job?.state), style = MaterialTheme.typography.titleMedium)
                    job?.reason?.let { Text(it) }
                    job?.stages?.filter { it.status != "ok" }?.forEach { it.reason?.let { reason -> Text(reason) } }
                    if (job.hasActivityReport()) {
                        TextButton(enabled = !saving, onClick = { selectedId = null; reportId = item.id }) { Text("View AI Report") }
                        TextButton(enabled = !saving, onClick = { downloadReport(item) }) { Text("Download AI Report") }
                    }
                    val alternative = availableProvider?.takeIf { it == "adaption" || it == "checkpoint" }
                    val providerAvailable = provider == "cloudflare" || provider == alternative
                    if (job?.state != "UNCERTAIN" && (job?.state !in setOf("COMPLETE", "PARTIAL") ||
                        alternative != null || !providerAvailable)) {
                        if (alternative != null || !providerAvailable) TextButton(enabled = !saving, onClick = {
                            provider = if (provider == "cloudflare") requireNotNull(alternative) else "cloudflare"
                            consent = false
                        }) { Text(when {
                            provider != "cloudflare" -> "Use Cloudflare synthesis"
                            alternative == "checkpoint" -> "Use trained SAAYA model"
                            else -> "Use Adaption hosted synthesis"
                        }) }
                        Row {
                            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !saving && providerAvailable)
                            Text(when (provider) {
                                "adaption" -> "Allow Cloudflare media analysis and Adaption Labs text synthesis. Originals stay on this phone."
                                "checkpoint" -> "Allow Cloudflare media analysis and text synthesis by the configured SAAYA checkpoint."
                                else -> "Allow Cloudflare to analyse selected media and descriptions. Originals stay on this phone."
                            })
                        }
                        if (!providerAvailable) Text("The selected synthesis provider is unavailable.")
                        TextButton(enabled = providerAvailable && consent && !saving && !(job?.state == "FAILED" && job.attempt >= 2), onClick = {
                            consent = false
                            perform(if (item.kind == "audio") "Audio analysis could not start. Verify Cloudflare WAV support and retry."
                                else "Could not advance analysis. Refresh and retry.") {
                                try {
                                    analysisProgress = "Preparing evidence…"
                                    repository.analyze(item, provider, { analysisProgress = it }) { updated ->
                                        pending = repository.pending()
                                        selectedId = updated.id
                                    }
                                } finally { analysisProgress = null }
                            }
                        }) { Text(when (job?.state) {
                            null -> "Analyse with AI"
                            "PAUSED", "FAILED" -> "Retry analysis"
                            "COMPLETE", "PARTIAL" -> "Generate another report"
                            else -> "Continue analysis"
                        }) }
                        if (job?.state == "FAILED" && job.attempt >= 2) Text("Retry limit reached.")
                    }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    analysisProgress?.let { Text(it) }
                    status?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(enabled = !saving, onClick = { selectedId = null }) { Text("Close") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { archiveTarget = item }) { Text(if (item.id == item.requestId) "Delete" else "Archive") } }
        )
    }
    saved.firstOrNull { it.id == reportId && it.analysis.hasActivityReport() }?.let { item ->
        AlertDialog(onDismissRequest = { if (!saving) reportId = null }, title = { Text("AI report") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityAiReport.content(item).forEach { paragraph ->
                            Text(paragraph.text, style = if (paragraph.title) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !saving, onClick = { downloadReport(item) }) { Text("Download AI Report") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { reportId = null }) { Text("Close") } })
    }
    archiveTarget?.let { item ->
        AlertDialog(onDismissRequest = { archiveTarget = null }, title = { Text(if (item.id == item.requestId) "Delete local capture?" else "Archive capture?") },
            text = { Text(if (item.id == item.requestId) "This permanently removes the original and report from this phone." else "This removes it from Activity. The private storage copy is retained.") },
            confirmButton = { TextButton(onClick = {
                archiveTarget = null
                perform("Could not archive this capture.") {
                    if (item.id == item.requestId) {
                        pending.firstOrNull { it.capture.requestId == item.requestId }?.let { repository.discard(it.capture) }
                        pending = repository.pending()
                    } else {
                        api.archive(item.id)
                        repository.clearPreview(item)
                        legacy = legacy.filterNot { it.id == item.id }
                    }
                    selectedId = null
                }
            }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Cancel") } })
    }
    selectedLocal?.let { capture ->
        AlertDialog(
            onDismissRequest = { selectedLocal = null },
            title = { Text(capture.kind.replaceFirstChar { it.titlecase() }) },
            text = {
                Column {
                    Text(displayTime(capture.capturedAt))
                    if (capture.kind != "note") TextButton(onClick = {
                        selectedLocal = null
                        localPreview = capture
                    }) { Text("Preview") }
                    TextButton(onClick = {
                        selectedLocal = null
                        draftId = capture.requestId
                        draftKind = capture.kind
                        draftTime = capture.capturedAt
                        details = capture.details
                        latitude = capture.latitude
                        longitude = capture.longitude
                        draftOpen = true
                    }) { Text("Finish details") }
                    TextButton(onClick = { selectedLocal = null; discardTarget = capture }) { Text("Discard") }
                }
            },
            confirmButton = { TextButton(onClick = { selectedLocal = null }) { Text("Close") } }
        )
    }
    localPreview?.let { capture ->
        AlertDialog(onDismissRequest = { localPreview = null }, title = { Text("Local capture") },
            text = { ActivityPreview(repository.media(capture.requestId, capture.kind), capture.kind) },
            confirmButton = { TextButton(onClick = { localPreview = null }) { Text("Close") } })
    }
    discardTarget?.let { capture ->
        AlertDialog(onDismissRequest = { discardTarget = null }, title = { Text("Discard local capture?") },
            text = { Text("This permanently removes the unsynced copy from this phone.") },
            confirmButton = { TextButton(onClick = {
                discardTarget = null
                perform("Could not discard the local capture.") {
                    repository.discard(capture)
                    pending = repository.pending()
                }
            }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discardTarget = null }) { Text("Cancel") } })
    }
}

private data class ActivityRecord(
    val date: String,
    val title: String,
    val description: String,
    val location: String,
    val evidence: String,
    val type: String
)

private fun activityRecord(kind: String, details: String, capturedAt: String, latitude: Double?, status: String): ActivityRecord {
    val type = kind.replaceFirstChar { it.titlecase() }
    return ActivityRecord(displayTime(capturedAt), type, details, if (latitude == null) "No location" else "Location attached", status, type)
}

@Composable
private fun EvidenceCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    iconColor: Color,
    iconBackground: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(145.dp),
        shape = RoundedCornerShape(20.dp),
        color = SaayaWhite,
        border = BorderStroke(1.dp, SaayaBorder),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(15.dp),
                color = iconBackground
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SaayaBlack
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 9.sp,
                    color = SaayaTextSecondary
                )
            }
        }
    }
}

@Composable
private fun ActivityRecordCard(
    record: ActivityRecord,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = SaayaWhite,
        border = BorderStroke(1.dp, SaayaBorder),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(105.dp),
                shape = RoundedCornerShape(13.dp),
                color = Color(0xFFF1F1F1)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (record.type) {
                            "Video" -> Icons.Outlined.Videocam
                            "Audio" -> Icons.Outlined.Mic
                            "Note" -> Icons.Outlined.Description
                            else -> Icons.Outlined.CameraAlt
                        },
                        contentDescription = null,
                        tint = Color(0xFF777777),
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = when (record.type) {
                            "Video" -> "VIDEO EVIDENCE"
                            "Audio" -> "AUDIO RECORDING"
                            "Note" -> "NOTE"
                            else -> "PHOTO EVIDENCE"
                        },
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF777777),
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = record.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SaayaBlack)
                    Spacer(Modifier.height(5.dp))
                    Text(text = record.description, fontSize = 9.sp, color = SaayaTextSecondary, lineHeight = 14.sp)
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, tint = SaayaTextSecondary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = record.location, fontSize = 8.sp, color = SaayaTextSecondary, modifier = Modifier.weight(1f))
                Text(text = record.evidence, fontSize = 8.sp, color = SaayaTextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Text(text = record.date, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = SaayaBlack)
        }
    }
}


private fun analysisStatus(state: String?): String = when (state) {
    null -> "Analysis not started"
    "COMPLETE" -> "Analysis ready"
    "PARTIAL" -> "Partial analysis"
    "UNCERTAIN" -> "Analysis outcome unverified"
    "RUNNING" -> "Analysis running"
    "PAUSED" -> "Analysis paused"
    "FAILED" -> "Analysis failed"
    else -> "More analysis steps available"
}

private fun ActivityAnalysis?.hasActivityReport(): Boolean =
    this != null && (report != null || (state in setOf("COMPLETE", "PARTIAL", "UNCERTAIN") &&
        stages.any { it.status == "ok" && it.result != null }))

@Composable
private fun ActivityPreview(file: File, kind: String) {
    when (kind) {
        "photo" -> {
            val image by produceState<android.graphics.Bitmap?>(null, file) {
                value = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeFile(file.path, options)
                }
            }
            image?.let { Image(it.asImageBitmap(), "Saved photo", Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
        }
        "video" -> {
            var view by remember(file) { mutableStateOf<VideoView?>(null) }
            AndroidView(factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setVideoPath(file.path)
                    view = this
                }
            }, modifier = Modifier.fillMaxWidth().height(200.dp))
            TextButton(onClick = { view?.start() }) { Text("Play video") }
            DisposableEffect(file) { onDispose { view?.stopPlayback() } }
        }
        "audio" -> {
            var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
            var playbackError by remember(file) { mutableStateOf(false) }
            TextButton(onClick = {
                if (player == null) {
                    val media = MediaPlayer()
                    try {
                        media.setDataSource(file.path)
                        media.setOnPreparedListener { it.start() }
                        media.setOnCompletionListener { it.release(); player = null }
                        media.setOnErrorListener { failed, _, _ ->
                            failed.release()
                            player = null
                            playbackError = true
                            true
                        }
                        player = media
                        media.prepareAsync()
                    } catch (_: Exception) {
                        media.release()
                        player = null
                        playbackError = true
                    }
                } else {
                    player?.release()
                    player = null
                }
            }) { Text(if (player == null) "Play audio" else "Stop audio") }
            if (playbackError) Text("This recording could not be played.")
            DisposableEffect(file) { onDispose { player?.release() } }
        }
    }
}
