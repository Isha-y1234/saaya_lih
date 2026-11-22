package com.project.saaya.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.project.saaya.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Base64
import retrofit2.HttpException

internal fun activityExtension(kind: String): String = when (kind) {
    "photo" -> "jpg"
    "video" -> "mp4"
    "audio" -> "wav"
    else -> error("Unsupported media")
}

internal class ActivityEvidenceRepository(context: Context, owner: String, val api: ActivityEvidenceApi) {
    private val local = File(context.noBackupFilesDir, "activity/$owner").apply { mkdirs() }
    private val cache = File(context.cacheDir, "activity-view/$owner").apply { mkdirs() }
    private val gson = Gson()
    private val transfer = EvidenceTransfer()

    fun media(id: String, kind: String) = File(local, "$id.${activityExtension(kind)}")
    private fun journal(id: String) = AtomicFile(File(local, "$id.json"))
    private fun attempt(id: String) = File(local, "$id.upload")
    // Legacy journals used queued to distinguish saved evidence from an unfinished details dialog.
    data class Entry(val capture: ActivityCapture, val queued: Boolean, val analysis: ActivityAnalysis? = null) {
        fun item(): ActivityItem = capture.run {
            ActivityItem(requestId, requestId, kind, capturedAt, details, latitude, longitude, fileSize,
                when (kind) { "photo" -> "image/jpeg"; "audio" -> "audio/wav"; "video" -> "video/mp4"; else -> null },
                analysis)
        }
    }

    private fun read(id: String): Entry? = try {
        decodeActivityJournal(journal(id).readFully().decodeToString(), gson)
    } catch (_: Exception) { null }

    suspend fun pending(): List<Entry> = withContext(Dispatchers.IO) {
        local.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { read(it.nameWithoutExtension) }
            .sortedByDescending { it.capture.capturedAt }
    }

    suspend fun save(capture: ActivityCapture, queued: Boolean = false) = withContext(Dispatchers.IO) {
        write(Entry(capture, queued, read(capture.requestId)?.analysis))
    }

    private fun write(entry: Entry) {
        val target = journal(entry.capture.requestId)
        val stream = target.startWrite()
        try {
            stream.write(gson.toJson(entry).encodeToByteArray())
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    suspend fun discard(capture: ActivityCapture) = withContext(Dispatchers.IO) {
        journal(capture.requestId).delete()
        attempt(capture.requestId).delete()
        if (capture.kind != "note") media(capture.requestId, capture.kind).delete()
    }

    suspend fun preview(item: ActivityItem): File = withContext(Dispatchers.IO) {
        if (item.id == item.requestId) {
            val original = media(item.requestId, item.kind)
            check(original.isFile && original.length() == item.fileSize) { "Local original unavailable" }
            return@withContext original
        }
        val grant = api.download(item.id)
        val target = File(cache, "${item.id}.${activityExtension(item.kind)}")
        if (!target.exists() || target.length() != item.fileSize) {
            transfer.download(grant.downloadUrl, target, item.fileSize)
            check(target.length() == item.fileSize)
        }
        target
    }

    suspend fun clearPreview(item: ActivityItem) = withContext(Dispatchers.IO) {
        if (item.kind != "note") File(cache, "${item.id}.${activityExtension(item.kind)}").delete()
    }

    suspend fun analyze(item: ActivityItem, provider: String, progress: (String) -> Unit, updated: suspend (ActivityItem) -> Unit) {
        val entry = withContext(Dispatchers.IO) { read(item.requestId) } ?: importLegacy(item)
        var job = entry.analysis?.takeUnless { it.state == "FAILED" }
            ?: ActivityAnalysis("READY", emptyList(), emptyList(), null, null)
        val reuseFindings = job.report != null
        var phase = "Cloudflare media preparation"
        suspend fun checkpoint(state: String, reason: String? = null, report: ActivityReport? = job.report) {
            job = job.copy(state = state, reason = reason, report = report)
            val next = entry.copy(analysis = job)
            withContext(Dispatchers.IO) { write(next) }
            updated(next.item())
        }
        val directory = File(cache, "derived-${entry.capture.requestId}")
        checkpoint("READY")
        try {
            val assets = if (item.kind == "note" || reuseFindings) emptyList() else {
                progress("Preparing media on this phone…")
                val audio = api.config().audioEnabled
                if (item.kind == "audio" && !audio) {
                    checkpoint("PAUSED", "Cloudflare WAV transcription is awaiting verification.")
                    return
                }
                val original = preview(entry.item())
                withContext(Dispatchers.IO) {
                    if (item.kind == "audio") listOf(DerivedAsset(original, "audio", 0))
                    else AnalysisPreprocessor().prepare(original, directory, 3, audio)
                }
            }
            for ((index, asset) in assets.withIndex()) {
                val previous = job.stages.indexOfFirst { it.kind == asset.kind && it.offsetMs == asset.offsetMs }
                if (previous >= 0 && job.stages[previous].status != "unavailable") continue
                progress("Analysing media ${index + 1} of ${assets.size}…")
                val encoded = withContext(Dispatchers.IO) {
                    check(asset.file.length() in 1..1_000_000)
                    Base64.encodeToString(asset.file.readBytes(), Base64.NO_WRAP)
                }
                phase = if (asset.kind == "audio") "Cloudflare Whisper" else "Cloudflare Vision"
                val result = api.analyzeLocal(LocalActivityTask(asset.kind, consent = true, media = encoded))
                if (result.status in setOf("retry", "quota")) {
                    checkpoint("PAUSED", "$phase: ${result.reason ?: "Retry later."}")
                    return
                }
                val stage = ActivityStage(asset.kind, result.status, result.reason, asset.offsetMs, null,
                    result.result?.let { gson.fromJson(it, ActivityStageResult::class.java) })
                val stages = job.stages.toMutableList()
                if (previous >= 0) stages[previous] = stage else stages.add(stage)
                job = job.copy(stages = stages)
                checkpoint("READY")
            }
            if (item.kind != "note" && job.stages.none { it.status == "ok" }) {
                val last = job.stages.lastOrNull { it.reason != null }
                val uncertain = job.stages.any { it.status == "uncertain" }
                checkpoint(if (uncertain) "UNCERTAIN" else "FAILED",
                    last?.reason ?: "Cloudflare returned no usable media findings.")
                return
            }
            phase = when (provider) {
                "checkpoint" -> "Adaption-trained SAAYA report synthesis"
                "adaption" -> "Adaption hosted report synthesis"
                else -> "Cloudflare report synthesis"
            }
            progress("Preparing your AI report…")
            fun reportContext(limit: Int) = gson.toJson(mapOf(
                "evidenceId" to entry.capture.requestId, "kind" to item.kind, "capturedAt" to item.capturedAt,
                "userDescription" to item.details.take(limit), "descriptionTruncated" to (item.details.length > limit),
                "sources" to job.stages.map { stage -> mapOf("kind" to stage.kind, "offsetMs" to stage.offsetMs,
                    "status" to stage.status, "transcript" to stage.result?.text?.take(minOf(800, limit)),
                    "observations" to stage.result?.observations.orEmpty().take(3).map { it.take(minOf(180, limit)) }) }))
            val context = reportContext(2000).takeIf { it.length <= 10000 } ?: reportContext(110)
            val result = api.analyzeLocal(LocalActivityTask("report", consent = true, context = context,
                synthesisProvider = provider, synthesisConsent = provider))
            val partial = job.stages.any { it.status != "ok" } ||
                (item.kind == "video" && job.stages.none { it.kind == "audio" && it.status == "ok" })
            val report = result.result?.takeIf { result.status == "ok" }?.let { gson.fromJson(it, ActivityReport::class.java) }
            val state = when {
                result.status == "uncertain" -> "UNCERTAIN"
                report == null -> "PAUSED"
                partial -> "PARTIAL"
                else -> "COMPLETE"
            }
            checkpoint(state, if (report != null) result.reason else "$phase: ${result.reason ?: "No valid report was returned."}",
                report ?: job.report)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: HttpException) {
            val reason = when (error.code()) {
                401 -> "Sign in again to analyse this capture."
                403 -> "Confirm consent for the selected analysis provider."
                409 -> "The selected provider is disabled or changed. Reopen Activity for the current provider settings."
                413 -> "The prepared analysis input exceeds the server limit."
                422 -> "The prepared analysis input was rejected. Your original is saved."
                429 -> "Activity analysis is busy or its daily allowance is exhausted. Try again later."
                503 -> activityUnavailableReason(gson, error)
                else -> "The server could not complete analysis. Your original is saved."
            }
            checkpoint("PAUSED", "$phase: $reason")
        } catch (error: Exception) {
            checkpoint("PAUSED", "$phase could not continue. The original is still saved. Try again when the service is reachable.")
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { directory.deleteRecursively() }
        }
    }

    private suspend fun importLegacy(item: ActivityItem): Entry {
        val capture = ActivityCapture(item.requestId, item.kind, item.capturedAt, item.details, item.latitude,
            item.longitude, item.fileSize)
        val source = if (item.kind == "note") null else preview(item)
        return withContext(Dispatchers.IO) {
            source?.copyTo(media(capture.requestId, capture.kind), overwrite = true)
            Entry(capture, true).also(::write)
        }
    }
}

private fun activityUnavailableReason(gson: Gson, error: HttpException): String {
    val detail = runCatching {
        val body = error.response()?.errorBody()?.string()
        gson.fromJson(body, com.google.gson.JsonObject::class.java)?.get("detail")?.asString
    }.getOrNull()
    return when (detail) {
        "Cloud analysis is not configured" -> "The Cloudflare Worker URL needs to be configured."
        "Cloud analysis free-plan setup is incomplete" -> "Cloudflare account credentials or the free-plan verification flag need attention."
        "Verified WAV transcription is disabled" -> "Cloudflare WAV transcription is awaiting verification."
        else -> "The analysis service is temporarily unavailable."
    }
}

internal fun decodeActivityJournal(json: String, gson: Gson): ActivityEvidenceRepository.Entry {
    val data = JsonParser.parseString(json).asJsonObject
    return if (data.has("capture")) gson.fromJson(data, ActivityEvidenceRepository.Entry::class.java)
    else ActivityEvidenceRepository.Entry(gson.fromJson(data, ActivityCapture::class.java), queued = true)
}
