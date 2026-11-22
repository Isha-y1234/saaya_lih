package com.project.saaya.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun AudioVerificationPanel(id: String, userId: String, api: EvidenceApiService) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val folder = remember(id,userId) { File(context.noBackupFilesDir,"audio-verification/$userId/$id").apply { mkdirs() } }
    var files by remember(folder) { mutableStateOf(folder.listFiles().orEmpty().filter { it.extension == "wav" }.sortedByDescending { it.lastModified() }) }
    var consent by remember(id,userId) { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Local compatibility samples only. Recording does not upload audio.") }
    var work by remember { mutableStateOf<Job?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var reviewedSamples by remember { mutableStateOf(emptySet<String>()) }
    val stop = remember { AtomicBoolean(false) }
    fun refresh() { files = folder.listFiles().orEmpty().filter { it.extension == "wav" }.sortedByDescending { it.lastModified() } }
    fun record() {
        if (!consent || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || recording || submitting) return
        if (files.size >= 5) { status = "Five samples are preserved. Use an existing sample for verification."; return }
        stop.set(false); recording = true
        status = "RECORDING · microphone active · maximum 30 seconds. Tap Stop to review."
        val file = File(folder,"${UUID.randomUUID()}.wav")
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date())
        File(file.path+".time").writeText(timestamp)
        work = scope.launch {
            try {
                NativeWavRecorder().record(file,stop)
                val info = withContext(Dispatchers.IO) { WavFormat.validate(file.readBytes()) }
                status = if (info.silent) "Silent sample preserved. Record clearly before submitting." else "Recording saved locally (${info.durationMs} ms). Play and review before Submit."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Recording unavailable or interrupted. Existing samples remain private." }
            finally { recording=false; refresh() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) record() else status = "Microphone permission denied. No recording started."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                stop.set(true); work?.cancel(); player?.release(); player=null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { stop.set(true); work?.cancel(); player?.release(); lifecycle.removeObserver(observer) }
    }
    Text("Whisper audio verification",style=MaterialTheme.typography.titleMedium)
    Text("Use non-sensitive speech. English: ‘This is a Saaya audio test. The sky is blue.’ Hindi: ‘यह साया का ऑडियो परीक्षण है। आज मौसम अच्छा है।’")
    Text("Production audio remains disabled until a real test passes. Verification samples are excluded from incident reports.")
    Row { Checkbox(consent,{consent=it},enabled=!recording&&!submitting); Text("I consent to microphone recording and explicitly submitting a selected test sample to Cloudflare.") }
    Text(status)
    Button(enabled=consent&&!submitting,onClick={
        if(recording) stop.set(true)
        else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) record()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text(if(recording) "Stop recording" else "Record local WAV sample") }
    files.forEach { file ->
        Text("Sample ${file.nameWithoutExtension.take(8)} · ${file.length()} bytes")
        TextButton(enabled=!recording&&!submitting,onClick={
            try {
                player?.release()
                player=MediaPlayer().apply { setDataSource(file.path); prepare(); setOnCompletionListener {
                    reviewedSamples=reviewedSamples+file.path;it.release();player=null;status="Sample reviewed. Submit only if it contains non-sensitive test speech."
                };start() }
                status="Playing local sample. Nothing uploaded."
            } catch (_: Exception) { player?.release();player=null;status="Sample could not be played. It may be interrupted or empty." }
        }) { Text("Play / review") }
        TextButton(enabled=player!=null,onClick={player?.release();player=null;status="Playback stopped."}) { Text("Stop playback") }
        TextButton(enabled=consent&&!recording&&!submitting&&file.path in reviewedSamples,onClick={
            player?.release();player=null;submitting=true
            scope.launch {
                try {
                    val config=api.analysisConfig(id)
                    check(config.verificationAllowed) { "Verification disabled" }
                    val bytes=withContext(Dispatchers.IO) {
                        check(file.length() in 46..WavFormat.MAX_FILE_BYTES.toLong())
                        file.readBytes().also { check(!WavFormat.validate(it).silent) }
                    }
                    val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                    val requestId=UUID.nameUUIDFromBytes("$id:${file.name}:$digest".toByteArray()).toString()
                    val timestamp=File(file.path+".time").readText()
                    val grant=api.analysisUpload(id,AnalysisAssetUpload(requestId,null,"audio",file.length(),0,digest,
                        verificationOnly=true,capturedAt=timestamp))
                    if(!grant.complete) withContext(Dispatchers.IO) {
                        EvidenceTransfer().put(requireNotNull(grant.uploadUrl),requireNotNull(grant.headers),file,"audio/wav"){}
                    }
                    api.analysisComplete(id,grant.assetId)
                    api.analysisCreate(id,AnalysisCreate(requestId,listOf(grant.assetId),"Non-sensitive compatibility sample",verificationOnly=true))
                    status="Sample submitted with consent. Refresh analyses and Continue analysis to run the cloud test. Original WAV preserved."
                } catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){status="Sample not submitted or confirmation unavailable. Verification may be disabled; check setup and retry. Original preserved."}
                finally{submitting=false}
            }
        }) { Text("Submit selected sample for verification") }
    }
}
