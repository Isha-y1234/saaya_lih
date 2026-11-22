package com.project.saaya.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.EvidenceRepository
import com.project.saaya.data.repository.EvidenceTransfer
import com.project.saaya.ui.state.evidencePermissions
import com.project.saaya.ui.state.evidenceCaptureHasSpace
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EvidenceScreen(id: String, userId: String, api: EvidenceApiService, onBack: () -> Unit, onReports: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // noBackupFilesDir is private, survives process death, and is excluded from cloud/device backup.
    if (!id.matches(Regex("[a-fA-F0-9]{24}")) || !userId.matches(Regex("[a-fA-F0-9]{24}"))) {
        Column(Modifier.padding(16.dp)) { Text("Incident unavailable."); TextButton(onClick = onBack) { Text("Back") } }
        return
    }
    val folder = remember(id, userId) {
        File(context.noBackupFilesDir, "evidence/$userId/$id").apply { mkdirs() }
    }
    val repository = remember(api) { EvidenceRepository(api) }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var page by remember(id) { mutableStateOf<EvidencePage?>(null) }
    var files by remember(folder) { mutableStateOf(emptyList<File>()) }
    var status by remember { mutableStateOf("Loading evidence…") }
    var busy by remember { mutableStateOf(false) }
    var uploadInProgress by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var previewEnabled by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var requestedVideo by remember { mutableStateOf(false) }
    val photo = remember { ImageCapture.Builder().build() }
    val video = remember { VideoCapture.withOutput(Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.SD)).build()) }
    val previewView = remember { PreviewView(context) }
    fun refreshFiles() { files = folder.listFiles().orEmpty().filter { it.extension in listOf("jpg", "mp4", "capture") }.sortedBy { it.name } }
    suspend fun refresh() {
        try { page = api.list(id); refreshFiles() }
        catch (error: HttpException) {
            if (error.code() in listOf(401, 403, 404)) page = null
            throw error
        }
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun hasSpace(): Boolean {
        val available = evidenceCaptureHasSpace(folder.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        if (!available) status = "Local evidence limit reached (200 MiB). Upload saved files before capturing more."
        return available
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (granted(Manifest.permission.CAMERA) && (!requestedVideo || granted(Manifest.permission.RECORD_AUDIO))) {
            previewEnabled = true; status = "Camera ready. Tap a capture button to begin."
        } else {
            val blocked = result.any { (permission, allowed) -> !allowed && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) }
            status = if (blocked) "Permission blocked. Open App permissions to allow camera or microphone, then enable the camera again."
                     else "Permission denied. Photos need camera only; video with audio also needs microphone. You can retry when ready."
        }
    }
    fun enableCamera(withAudio: Boolean) {
        if (!hasSpace()) return
        requestedVideo = withAudio
        val needed = evidencePermissions(withAudio)
        if (needed.all(::granted)) {
            previewEnabled = true; status = "Camera ready. Tap a capture button to begin."
        } else permissions.launch(needed)
    }
    LaunchedEffect(id, userId) {
        refreshFiles()
        try { refresh(); status = "Evidence is private. Capture and upload only when you choose." }
        catch (_: Exception) { status = "Could not load evidence. Check your connection and retry." }
    }
    LaunchedEffect(page?.canUpload) {
        if (page?.canUpload != true) { recording?.stop(); previewEnabled = false }
    }
    DisposableEffect(owner, previewEnabled, requestedVideo) {
        var disposed = false
        if (previewEnabled) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    val camera = future.get()
                    provider = camera
                    val selector = when {
                        camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw IllegalStateException()
                    }
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    if (requestedVideo) camera.bindToLifecycle(owner, selector, preview, video)
                    else camera.bindToLifecycle(owner, selector, preview, photo)
                    cameraReady = true
                } catch (_: Exception) {
                    cameraReady = false; previewEnabled = false
                    status = "Camera unavailable. Close other camera apps and retry. Saved files remain available."
                }
            }, executor)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recording?.stop(); recording = null; previewEnabled = false; cameraReady = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true; owner.lifecycle.removeObserver(observer)
            recording?.stop(); recording = null
            provider?.unbindAll(); cameraReady = false
        }
    }
    fun captureFile(extension: String) = File(folder, "${UUID.randomUUID()}.$extension").also {
        // Keep recording-start time separate from file modification time (which reflects recording end).
        runCatching { File(folder, "${it.nameWithoutExtension}.capture-time").writeText(System.currentTimeMillis().toString()) }
    }
    fun captured(file: File, extension: String) {
        val final = File(folder, "${file.nameWithoutExtension}.$extension")
        if (file.renameTo(final)) status = "Captured locally. Choose Upload to save privately."
        else status = "Could not finalize local capture. File preserved."
        capturing = false; refreshFiles()
    }
    fun upload(file: File) {
        previewEnabled = false
        busy = true; uploadInProgress = true; progress = 0f; status = "Uploading evidence…"
        scope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(File(folder, "${file.nameWithoutExtension}.capture-time").takeIf { it.exists() }
                    ?.readText()?.toLongOrNull() ?: file.lastModified()))
                repository.upload(id, file, timestamp) { progress = it }
                status = "Uploaded. Backend confirmed evidence is saved."
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "Uploaded and confirmed. Could not refresh the evidence list yet." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: HttpException) {
                status = when (error.code()) {
                    401 -> "Session expired. Sign in again. Local file preserved."
                    403, 404 -> "Evidence is unavailable for this account. Local file preserved."
                    409 -> "Incident ended or upload could not be verified. Local file preserved."
                    410 -> "Upload retry window ended. Local file preserved."
                    413 -> "File exceeds the server size limit. Local file preserved."
                    else -> "Upload not confirmed. Local file preserved. Retry when connected."
                }
            } catch (_: Exception) { status = "Upload not confirmed. Local file preserved. Retry when connected." }
            finally { busy = false; uploadInProgress = false; refreshFiles() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack, enabled = !capturing) { Text("Back") }
        Text("Emergency evidence", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onReports) { Text("Incident Reports") }
        Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (uploadInProgress) { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${(progress * 100).toInt()}% transferred · awaiting backend confirmation") }
        else if (busy) CircularProgressIndicator()
        TextButton(enabled = !busy && !capturing, onClick = { scope.launch {
            try { refresh(); status = "Evidence refreshed." } catch (_: Exception) { status = "Could not refresh. Retry when connected." }
        } }) { Text("Refresh evidence") }
        if (page?.canUpload == true) {
            Text("Photos use camera only. Videos include audio and stop after 30 seconds or 45 MiB. Recording stops when you leave this screen.")
            TextButton(enabled = !busy && !capturing, onClick = { enableCamera(false) }) { Text("Enable photo camera") }
            TextButton(enabled = !busy && !capturing, onClick = { enableCamera(true) }) { Text("Enable video camera and audio") }
            TextButton(enabled = !busy && !capturing, onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))) }) { Text("App permissions") }
            if (previewEnabled) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(220.dp))
            Button(enabled = cameraReady && !requestedVideo && !capturing && !busy, onClick = {
                if (!hasSpace()) return@Button
                capturing = true; status = "Capturing photo…"
                val file = captureFile("capture")
                try {
                    photo.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) { captured(file, "jpg") }
                            override fun onError(error: ImageCaptureException) { capturing = false; refreshFiles(); status = "Photo interrupted. Retry capture." }
                        })
                } catch (_: Exception) { capturing = false; status = "Photo capture unavailable. Retry." }
            }) { Text("Capture photo") }
            Button(enabled = cameraReady && requestedVideo && !busy && (!capturing || recording != null), onClick = {
                if (recording != null) { recording?.stop(); recording = null }
                else if (!granted(Manifest.permission.RECORD_AUDIO)) enableCamera(true)
                else {
                    if (!hasSpace()) return@Button
                    val file = captureFile("capture")
                    try {
                        capturing = true; status = "RECORDING video with audio · tap Stop recording"
                        recording = video.output.prepareRecording(context, FileOutputOptions.Builder(file)
                            .setDurationLimitMillis(30_000).setFileSizeLimit(45L * 1024 * 1024).build())
                            .withAudioEnabled().start(executor) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    recording?.close(); recording = null
                                    if (!event.hasError() || event.error in listOf(VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                                            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE)) captured(file, "mp4")
                                    else { capturing = false; refreshFiles(); status = "Recording interrupted. Partial file preserved; capture again." }
                                }
                            }
                    } catch (_: Exception) { capturing = false; status = "Could not start recording. Check camera and microphone permissions." }
                }
            }) { Text(if (recording != null) "Stop recording" else "Record short video with audio") }
        } else Text("Only the owner of an active SOS can capture and upload. Saved evidence remains accessible after SOS ends.")
        if (files.isNotEmpty()) Text("Local evidence awaiting confirmation")
        files.forEach { file ->
            Text("${if (file.extension == "mp4") "Video" else if (file.extension == "jpg") "Photo" else "Interrupted capture"} · ${file.length() / 1024} KiB")
            if (file.extension != "capture") TextButton(enabled = !busy && !capturing, onClick = { upload(file) }) { Text("Upload / Retry confirmation") }
        }
        Text("Saved evidence")
        page?.evidence.orEmpty().forEach { item ->
            Text("${if (item.mediaType == "video/mp4") "Video" else "Photo"} · ${item.fileSize / 1024} KiB · ${item.uploadedAt}")
            TextButton(enabled = !busy && !capturing, onClick = {
                busy = true; status = "Downloading selected evidence…"
                scope.launch {
                    try {
                        val grant = api.download(id, item.id)
                        val dir = File(context.cacheDir, "evidence-view").apply { mkdirs() }
                        val file = File(dir, "${UUID.randomUUID()}.${if (item.mediaType == "video/mp4") "mp4" else "jpg"}")
                        withContext(Dispatchers.IO) { EvidenceTransfer().download(grant.downloadUrl, file) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.evidence", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mediaType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        status = "Downloaded selected evidence. No automatic playback or repeated downloads."
                    } catch (_: Exception) { status = "Could not download or open evidence. Check connection and installed media viewers." }
                    finally { busy = false }
                }
            }) { Text("Download and open selected ${if (item.mediaType == "video/mp4") "video" else "photo"}") }
        }
        AnalysisPanel(id, userId, api, page?.evidence.orEmpty())
        if (page?.canUpload == true) AudioVerificationPanel(id, userId, api)
        if (page?.nextBefore != null) TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { try {
                val older = api.list(id, page?.nextBefore)
                page = older.copy(evidence = (page?.evidence.orEmpty() + older.evidence).distinctBy { it.id })
            } catch (_: Exception) { status = "Could not load older evidence." } finally { busy = false } }
        }) { Text("Load older evidence") }
    }
}
