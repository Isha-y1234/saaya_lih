package com.project.saaya.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.AnalysisPreprocessor
import com.project.saaya.data.repository.EvidenceTransfer
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Composable
fun AnalysisPanel(id: String, userId: String, api: EvidenceApiService, evidence: List<EvidenceItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consent by remember(id, userId) { mutableStateOf(false) }
    var notes by remember(id, userId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Analysis is optional. Original evidence stays private in B2.") }
    var jobs by remember(id, userId) { mutableStateOf(emptyList<AnalysisJob>()) }
    suspend fun refresh() { jobs = api.analysisJobs(id).jobs }
    LaunchedEffect(id, userId) { try { refresh() } catch (_: Exception) { status = "Analysis unavailable. Refresh to retry." } }
    Text("AI incident analysis", style = MaterialTheme.typography.titleLarge)
    Text("Cloud AI may be inaccurate. Frames are sampled; speech and participant statements are unverified. No person identification, legal conclusions or automatic SOS actions.")
    Row {
        Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
        Text("I consent to sending selected evidence frames, available audio, incident chat and notes to Cloudflare for AI analysis.")
    }
    OutlinedTextField(value = notes, onValueChange = { notes = it.take(2000) }, label = { Text("Optional incident notes (unverified statement)") }, enabled = !busy)
    Text(status)
    if (busy) CircularProgressIndicator()
    evidence.filter { it.ownerId == userId }.forEach { item ->
        TextButton(enabled = consent && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val config = api.analysisConfig(id)
                    val capturedNotes = notes
                    val assets = withContext(Dispatchers.IO) {
                        val directory = File(context.noBackupFilesDir, "analysis/$userId/$id/${item.id}").apply { mkdirs() }
                        check(directory.usableSpace > item.fileSize + 12L * 1024 * 1024) { "Insufficient phone storage" }
                        val original = File(directory, "original.${if (item.mediaType == "video/mp4") "mp4" else "jpg"}")
                        if (!original.exists() || original.length() != item.fileSize) {
                            val grant = api.download(id, item.id)
                            EvidenceTransfer().download(grant.downloadUrl, original, item.fileSize)
                            check(original.length() == item.fileSize)
                        }
                        // Original B2 recording is never modified. Temporary phone copy is reusable after interruption.
                        AnalysisPreprocessor().prepare(original, directory, config.maxFrames.coerceIn(1,4), config.audioEnabled)
                    }
                    val assetIds = mutableListOf<String>()
                    for (asset in assets) {
                        val digest = withContext(Dispatchers.IO) {
                            val hash = MessageDigest.getInstance("SHA-256")
                            asset.file.inputStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                            }
                            hash.digest().joinToString("") { "%02x".format(it) }
                        }
                        // Deterministic identity survives app restarts and lost upload responses.
                        val requestId = UUID.nameUUIDFromBytes("${item.id}:${asset.kind}:${asset.offsetMs}:$digest".toByteArray()).toString()
                        val grant = api.analysisUpload(id, AnalysisAssetUpload(requestId, item.id, asset.kind,
                            asset.file.length(), asset.offsetMs, digest))
                        if (!grant.complete) withContext(Dispatchers.IO) {
                            EvidenceTransfer().put(requireNotNull(grant.uploadUrl), requireNotNull(grant.headers), asset.file,
                                if (asset.kind == "frame") "image/jpeg" else "audio/wav") { }
                        }
                        api.analysisComplete(id, grant.assetId)
                        assetIds += grant.assetId
                    }
                    val requestId = UUID.nameUUIDFromBytes((assetIds.sorted().joinToString() + capturedNotes).toByteArray()).toString()
                    api.analysisCreate(id, AnalysisCreate(requestId, assetIds, capturedNotes))
                    refresh()
                    status = if (config.audioEnabled) "Analysis prepared. Tap Continue analysis to process each cloud stage."
                        else "Visual analysis prepared. Audio unavailable until exact-model WAV compatibility is verified."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: OutOfMemoryError) { status = "Phone memory insufficient. Original evidence preserved; retry later." }
                catch (_: Exception) { status = "Analysis preparation unavailable. Originals preserved. Check storage, session and connection, then retry." }
                finally { busy = false }
            }
        }) { Text("Prepare analysis for ${if (item.mediaType == "video/mp4") "video" else "photo"} ${item.id.takeLast(6)}") }
    }
    TextButton(enabled = !busy, onClick = { scope.launch {
        try { refresh(); status = "Analysis list refreshed." }
        catch (_: Exception) { jobs = emptyList(); status = "Analysis unavailable for this session. Retry after signing in." }
    } }) { Text("Refresh analyses") }
    jobs.forEach { job ->
        Text("${job.id.takeLast(6)} · ${job.state}")
        job.reason?.let { Text(it) }
        if (job.state !in listOf("COMPLETE", "PARTIAL")) TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    api.analysisAdvance(id, job.id); refresh()
                    status = "Cloud stage checkpoint saved. Continue until report is ready."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { jobs = emptyList(); status = "Analysis unavailable. Refresh to recover saved checkpoints." }
                finally { busy = false }
            }
        }) { Text("Continue analysis") }
        job.report?.takeIf { it.isJsonObject }?.asJsonObject?.let { report ->
            Text(if (report.get("verificationOnly")?.asBoolean == true) "Compatibility sample · excluded from incident reports" else "AI report · unverified", style = MaterialTheme.typography.titleMedium)
            report.getAsJsonArray("timeline")?.forEach { entry ->
                val line = entry.asJsonObject
                Text("${line.get("timestamp")?.takeUnless { it.isJsonNull }?.asString ?: "Time unavailable"} · ${line.get("category")?.asString ?: "Unverified"}")
                line.get("reference")?.let { Text("Original evidence: ${it.asString}") }
                line.get("text")?.let { Text(it.asString) }
                line.get("result")?.takeUnless { it.isJsonNull }?.let { Text(analysisText(it)) }
                line.get("reason")?.takeUnless { it.isJsonNull }?.let { Text(it.asString) }
            }
            report.get("interpretation")?.takeUnless { it.isJsonNull }?.let { Text(analysisText(it)) }
            report.get("notes")?.takeUnless { it.isJsonNull }?.let { Text("Participant notes (unverified): ${it.asString}") }
            report.getAsJsonArray("limitations")?.forEach { Text(it.asString, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

private fun analysisText(value: com.google.gson.JsonElement): String {
    if (!value.isJsonObject) return "AI output unavailable"
    return value.asJsonObject.entrySet().joinToString("\n") { (key, item) ->
        val text = if (item.isJsonArray) item.asJsonArray.joinToString("; ") {
            if (it.isJsonPrimitive) it.asString else if (key == "words" && it.isJsonObject) {
                val word = it.asJsonObject
                "${word.get("start")?.asDouble}–${word.get("end")?.asDouble}s: ${word.get("word")?.asString.orEmpty()}"
            } else "Unverified detail"
        } else if (item.isJsonPrimitive) item.asString else ""
        "$key: $text"
    }
}
